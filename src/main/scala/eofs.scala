/* Computes the EOFs of large climate datasets with no missing data
 *
 */

package org.apache.spark.mllib.climate
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.spark.storage.StorageLevel
import org.apache.spark.mllib.linalg.{Matrices, DenseMatrix, Matrix, DenseVector, Vector, SparseVector, Vectors}
import org.apache.spark.mllib.linalg.EigenValueDecomposition
import org.apache.spark.mllib.linalg.distributed._
import org.apache.spark.sql.{SQLContext, Row => SQLRow}
import breeze.linalg.{DenseMatrix => BDM, DenseVector => BDV, Axis, qr, svd, sum, SparseVector => BSV}
import breeze.linalg.{norm, diag, accumulate}
import breeze.numerics.{sqrt => BrzSqrt}
import math.{ceil, log}
import scala.collection.mutable.ArrayBuffer

import java.util.Arrays
import java.io.{DataOutputStream, BufferedOutputStream, FileOutputStream, File}
import java.util.Calendar
import java.text.SimpleDateFormat
import org.apache.hadoop.io.compress.DefaultCodec

object computeEOFs {

  def report(message: String, verbose: Boolean = true) = {
    val now = Calendar.getInstance().getTime()
    val formatter = new SimpleDateFormat("H:m:s")

    if(verbose) {
      println("STATUS REPORT (" + formatter.format(now) + "): " + message)
    }
  }

  case class EOFDecomposition(leftVectors: DenseMatrix, singularValues: DenseVector, rightVectors: DenseMatrix) {
    def U: DenseMatrix = leftVectors
    def S: DenseVector = singularValues
    def V: DenseMatrix = rightVectors
  }

  def fromBreeze(mat: BDM[Double]): DenseMatrix = {
    new DenseMatrix(mat.rows, mat.cols, mat.data, mat.isTranspose)
  }

  def main(args: Array[String]) = {
    val conf = new SparkConf().setAppName("ClimateEOFs")
    conf.set("spark.task.maxFailures", "1")
    val sc = new SparkContext(conf)
    sys.addShutdownHook( { sc.stop() } )
    appMain(sc, args)
  }

  def appMain(sc: SparkContext, args: Array[String]) = {
    val matformat = args(0)
    val inpath = args(1)
    val maskpath = args(2)
    val numrows = args(3).toInt
    val numcols = args(4).toLong
    val preprocessMethod = args(5)
    val numeofs = args(6).toInt
    val outdest = args(7)
    
    val (mat, mean, rowindices, numnewrows) = loadCSVClimateDataSubset(sc, matformat, inpath, maskpath, numrows, numcols, preprocessMethod)
    //val (mat, mean)= loadCSVClimateData(sc, matformat, inpath, maskpath, numrows, numcols, preprocessMethod)

    val (u, v) = getLowRankFactorization(mat, numeofs)
    val climateEOFs = convertLowRankFactorizationToEOFs(u, v)
    writeOut(outdest, climateEOFs, mean, rowindices)

    report(s"U - ${climateEOFs.U.numRows}-by-${climateEOFs.U.numCols}")
    report(s"S - ${climateEOFs.S.size}")
    report(s"V - ${climateEOFs.V.numRows}-by-${climateEOFs.V.numCols}")
    val errorvariance = calcSSE(mat, climateEOFs.U.toBreeze.asInstanceOf[BDM[Double]], 
      diag(BDV(climateEOFs.S.toArray)) * climateEOFs.V.toBreeze.asInstanceOf[BDM[Double]].t)
    val approxvariance = climateEOFs.S.toArray.map(math.pow(_,2)).sum
    val datavariance = mat.rows.map(x => x.vector.toArray.map(math.pow(_,2)).sum).sum
    report(s"Variance of low-rank approximation: $approxvariance")
    report(s"Error variance: $errorvariance")
    report(s"Data variance: $datavariance")
    report(s"$numeofs EOFs explain ${approxvariance/datavariance} of the variance with ${errorvariance/datavariance} relative error")

  }

  def calcSSE(mat: IndexedRowMatrix, lhsTall: BDM[Double], rhsFat: BDM[Double]) : Double = {
      val sse = mat.rows.treeAggregate(BDV.zeros[Double](1))(
        seqOp = (partial: BDV[Double], row: IndexedRow) => {
          val reconstructed = (lhsTall(row.index.toInt, ::) * rhsFat).t
          partial(0) += math.pow(norm(row.vector.toBreeze.asInstanceOf[BDV[Double]] - reconstructed), 2)
          partial
        },
        combOp = (partial1, partial2) => partial1 += partial2,
        depth = 8
      )
      sse(0)
  }

  def writeOut(outdest: String, eofs: EOFDecomposition, mean: BDV[Double], rowindices: BDV[Int]) {
    val outf = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(outdest))))
    dumpMat(outf, eofs.U.toBreeze.asInstanceOf[BDM[Double]])
    dumpMat(outf, eofs.V.toBreeze.asInstanceOf[BDM[Double]])
    dumpV(outf, eofs.S.toBreeze.asInstanceOf[BDV[Double]])
    dumpV(outf, mean)
    dumpVI(outf, rowindices)
  }

  def dumpV(outf: DataOutputStream, v: BDV[Double]) = {
    outf.writeInt(v.length) 
    for(i <- 0 until v.length) {
      outf.writeDouble(v(i))
    }
  }

  def dumpVI(outf: DataOutputStream, v: BDV[Int]) = {
    outf.writeInt(v.length) 
    for(i <- 0 until v.length) {
      outf.writeInt(v(i))
    }
  }

  def dumpMat(outf: DataOutputStream, mat: BDM[Double]) = {
    outf.writeInt(mat.rows)
    outf.writeInt(mat.cols)
    for(i <- 0 until mat.rows) {
      for(j <- 0 until mat.cols) {
        outf.writeDouble(mat(i,j))
      }
    }
  }

  // load a subset of the rows, and mean center, assume is masked
  def loadCSVClimateDataSubset(sc: SparkContext, matformat: String, inpath: String,
    maskpath: String, numrows: Int, numcols: Long, preprocessMethod: String) :
  Tuple4[IndexedRowMatrix, BDV[Double], BDV[Int], Int] = {

    var omittedrows = sc.textFile(maskpath).map(x => x.split(",")).map(x => x(1).toInt).distinct().collect().sortBy(identity)
    report(s"Loaded mask: omitting ${omittedrows.length} locations")

    var rawrows = sc.textFile(inpath).map(x => x.split(",")).
      map(x => (x(1).toInt, (x(0).toInt, x(2).toDouble))).
      filter(x => x._2._1 % 10 == 0).repartition(sc.defaultParallelism * 3)
      //repartition(sc.defaultParallelism * 9)

    val rows = rawrows.filter(x => Arrays.binarySearch(omittedrows, x._1) < 0).
      groupByKey.map(x => new IndexedRow(x._1, new DenseVector(x._2.toSeq.sortBy(_._1).map(_._2).toArray)))
    rows.persist(StorageLevel.MEMORY_AND_DISK_SER)
    report("Loaded rows")

    val distinctrowids = rows.map( x => x.index.toInt ).distinct().collect().sortBy(identity)
    report(s"Got ${distinctrowids.length} unique IDs")
    def getrowid(i: Int) = Arrays.binarySearch(distinctrowids, i)

    val numnewrows = rows.first().vector.toArray.length
    val reindexedrows = rows.map(x => new IndexedRow(getrowid(x.index.toInt), x.vector))
    reindexedrows.persist(StorageLevel.MEMORY_AND_DISK_SER)
    report("Indexed rows")
    rows.unpersist()

    val mat = new IndexedRowMatrix(reindexedrows, numcols, numnewrows)
    val mean = getRowMeans(mat)
    report("Computed mean")
    val centeredmat = subtractMean(mat, mean)
    centeredmat.rows.persist(StorageLevel.MEMORY_AND_DISK_SER)
    centeredmat.rows.count()

    reindexedrows.unpersist()
    report("Got centered matrix")
    (centeredmat, mean, BDV[Int](distinctrowids), numnewrows)
  }

  // For now, assume input is always csv and that rows are all observed, and that only mean centering is desired
  def loadCSVClimateData(sc: SparkContext, matformat: String, inpath: String, maskpath: String, numrows: Int, 
    numcols: Long, preprocessMethod: String) : Tuple2[IndexedRowMatrix, BDV[Double]] = {
    val rows = if ("maskpath" eq "notmasked") {
        sc.textFile(inpath).map(x => x.split(",")).
          map(x => (x(1).toInt, (x(0).toInt, x(2).toDouble))).
          groupByKey.map(x => new IndexedRow(x._1, new 
            DenseVector(x._2.toSeq.sortBy(_._1).map(_._2).toArray)))
    } else {
      var omittedrows = sc.textFile(maskpath).map(x => x.split(",")).map(x => x(1).toInt).distinct().collect().sortBy(identity)
      report(s"Loaded mask: omitting ${omittedrows.length} locations")
      sc.textFile(inpath).repartition(sc.defaultParallelism *3).map(x => x.split(",")).
        map(x => (x(1).toInt, (x(0).toInt, x(2).toDouble))).
        filter(x => Arrays.binarySearch(omittedrows, x._1) < 0).
        groupByKey.map(x => new IndexedRow(x._1, new
          DenseVector(x._2.toSeq.sortBy(_._1).map(_._2).toArray)))
    }
    rows.persist(StorageLevel.MEMORY_AND_DISK_SER)
    report("Loaded rows")

    val distinctrowids = rows.map( x => x.index.toInt ).distinct().collect().sortBy(identity)
    report(s"Got ${distinctrowids.length} unique IDs")
    def getrowid(i: Int) = Arrays.binarySearch(distinctrowids, i)

    val reindexedrows = rows.map(x => new IndexedRow(getrowid(x.index.toInt), x.vector))
    reindexedrows.persist(StorageLevel.MEMORY_AND_DISK_SER)
    report("Indexed rows")
    val mat = new IndexedRowMatrix(reindexedrows, numcols, numrows)
    val mean = getRowMeans(mat)
    report("Computed mean")
    val centeredmat = subtractMean(mat, mean)
    centeredmat.rows.persist(StorageLevel.MEMORY_AND_DISK_SER)

    reindexedrows.unpersist()
    centeredmat.rows.count()
    report("Got centered matrix")
    val squaredfrobnorm = centeredmat.rows.map(x => x.vector.toArray.map(x=>x*x).sum).reduce((a,b)=>a+b)
    report(s"Squared Frobenius norm of centered matrix: $squaredfrobnorm")

    (centeredmat, mean)
  }

  def writeIndexedRowMatrix(mat: IndexedRowMatrix, path: String) = {
    mat.rows.map(x => x.index.toString + "," + x.vector.toArray.mkString(",")).saveAsTextFile(path, classOf[DefaultCodec])
  }

  // returns a column vector of the means of each row
  def getRowMeans(mat: IndexedRowMatrix) : BDV[Double] = {
    val scaling = 1.0/mat.numCols
    val mean = BDV.zeros[Double](mat.numRows.toInt)
    val meanpairs = mat.rows.map( x => (x.index, scaling * x.vector.toArray.sum)).collect()
    meanpairs.foreach(x => mean(x._1.toInt) = x._2)
    mean
  }

  def subtractMean(mat: IndexedRowMatrix, mean: BDV[Double]) : IndexedRowMatrix = {
    val centeredrows = mat.rows.map(x => new IndexedRow(x.index, new DenseVector(x.vector.toArray.map(v => v - mean(x.index.toInt)))))
    new IndexedRowMatrix(centeredrows, mat.numRows, mat.numCols.toInt)
  }

  def convertLowRankFactorizationToEOFs(u : DenseMatrix, v : DenseMatrix) : EOFDecomposition = {
    val vBrz = v.toBreeze.asInstanceOf[BDM[Double]]
    val vsvd = svd.reduced(vBrz)
    // val diff = vsvd.U*diag(BDV(vsvd.S.data))*fromBreeze(vsvd.Vt.t).toBreeze.asInstanceOf[BDM[Double]].t - vBrz
    // report(s"Sanity check (should be 0): ${diff.data.map(x => x*x).sum}")
    EOFDecomposition(fromBreeze(u.toBreeze.asInstanceOf[BDM[Double]] * vsvd.U), new DenseVector(vsvd.S.data), fromBreeze(vsvd.Vt.t))
  }

  // returns U V with k columns so that U*V.t is an optimal rank-k approximation to mat
  def getLowRankFactorization(mat: IndexedRowMatrix, rank: Int) : Tuple2[DenseMatrix, DenseMatrix] = {
    val tol = 1e-13
    val maxIter = 30
    val covOperator = ( v: BDV[Double] ) => multiplyCovarianceBy(mat, fromBreeze(v.toDenseMatrix).transpose).toBreeze.asInstanceOf[BDM[Double]].toDenseVector
    val (lambda, u) = EigenValueDecomposition.symmetricEigs(covOperator, mat.numCols.toInt, rank, tol, maxIter)
    report(s"Square Frobenius norm of approximate row basis for data: ${u.data.map(x=>x*x).sum}")
    val Xlowrank = mat.multiply(fromBreeze(u)).toBreeze()
    val qr.QR(q,r) = qr.reduced(Xlowrank)
    report(s"Square Frobenius norms of Q,R: ${q.data.map(x=>x*x).sum}, ${r.data.map(x=>x*x).sum}") 
    (fromBreeze(q), fromBreeze(r*u.t)) 
  }

  // computes BA where B is a local matrix and A is distributed: let b_i denote the
  // ith col of B and a_i denote the ith row of A, then BA = sum(b_i a_i)
  def leftMultiplyBy(mat: IndexedRowMatrix, lhs: DenseMatrix) : DenseMatrix = {
   report(s"Left multiplying a ${mat.numRows}-by-${mat.numCols} matrix by a ${lhs.numRows}-by-${lhs.numCols} matrix")
   val lhsFactor = mat.rows.context.broadcast(lhs.toBreeze.asInstanceOf[BDM[Double]])

   val result =
     mat.rows.treeAggregate(BDM.zeros[Double](lhs.numRows.toInt, mat.numCols.toInt))(
       seqOp = (U: BDM[Double], row: IndexedRow) => {
         val rowBrz = row.vector.toBreeze.asInstanceOf[BDV[Double]]
         U += (lhsFactor.value)(::, row.index.toInt) * rowBrz.t
       },
       combOp = (U1, U2) => U1 += U2, depth = 8
     )
   fromBreeze(result)
  }

  // Returns `1/n * mat.transpose * mat * rhs`
  def multiplyCovarianceBy(mat: IndexedRowMatrix, rhs: DenseMatrix): DenseMatrix = {
    report(s"Going to multiply the covariance operator of a ${mat.numRows}-by-${mat.numCols} matrix by a ${rhs.numRows}-by-${rhs.numCols} matrix")
    val rhsBrz = rhs.toBreeze.asInstanceOf[BDM[Double]]
    val result = 
      mat.rows.treeAggregate(BDM.zeros[Double](mat.numCols.toInt, rhs.numCols))(
        seqOp = (U: BDM[Double], row: IndexedRow) => {
          val rowBrz = row.vector.toBreeze.asInstanceOf[BDV[Double]]
          U += row.vector.toBreeze.asInstanceOf[BDV[Double]].asDenseMatrix.t * (row.vector.toBreeze.asInstanceOf[BDV[Double]].asDenseMatrix * rhs.toBreeze.asInstanceOf[BDM[Double]])
        },
        combOp = (U1, U2) => U1 += U2
      )
    fromBreeze(1.0/mat.numRows * result)
  }
}

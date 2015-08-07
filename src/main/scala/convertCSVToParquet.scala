package org.apache.spark.climate
import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.storage.StorageLevel
import org.apache.spark.mllib.linalg.{DenseVector, SparseVector}
import org.apache.spark.mllib.linalg.distributed.{IndexedRow, IndexedRowMatrix}
import org.apache.spark.sql.{SQLContext, Row => SQLRow}
import java.util.Arrays

object CSVToParquet {
  def main(args: Array[String]) = {
    val conf= new SparkConf().setAppName("CSV to Parquet convertor").set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    val sc = new SparkContext(conf)

    convertCSVToScala(sc, args)
  }

  def convertCSVToScala(sc: SparkContext, args: Array[String]) = {
    if(args.length != 2) {
      Console.err.println("Expected args: inpath outpath") 
      System.exit(1)
    }

    val sqlctx = new org.apache.spark.sql.SQLContext(sc)
    import sqlctx.implicits._

    val valsinpath = args(0)
    val outpath = args(1)

    val valsrows = sc.textFile(valsinpath).map(_.split(",")).map(x => (x(1).toInt, (x(0).toInt, x(2).toDouble))).groupByKey.map(x => (x._1, x._2.toSeq.sortBy(_._1)))

    val newValsRows = valsrows.map(x => {
      val values = x._2.map(y => y._2).toArray
      new IndexedRow(x._1, new DenseVector(values))
    }).toDF
    newValsRows.saveAsParquetFile(outpath)
  }
}

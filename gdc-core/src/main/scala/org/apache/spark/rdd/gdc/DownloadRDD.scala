/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional logInformation regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.spark.rdd.gdc

import java.io._
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.executor.DataReadMethod
import org.apache.spark.rdd.RDD
import org.apache.spark.util.ShutdownHookManager
import org.apache.spark.util.gdc._
import org.apache.spark._

import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/**
  * Created by alvsanand on 11/10/16.
  */
class DownloadRDD[A <: DownloadFile : ClassTag](sc: SparkContext,
                                                files: Array[A],
                                                downloaderFactory: DownloaderFactory[A],
                                                downloaderParams: Map[String, String],
                                                charset: String = "UTF-8",
                                                maxRetries: Int = 3
                                               ) extends RDD[String](sc, Nil) with HasDownloadFileRange with Logging {

  private val GZIP_MAGIC_LENGTH = 2

  @DeveloperApi
  override def compute(split: Partition, context: TaskContext): InterruptibleIterator[String] = {
    val iter = new Iterator[String] {
      logInfo("Input partition: " + split)

      val inputMetrics = context.taskMetrics.getInputMetricsForReadMethod(DataReadMethod.Network)

      val count = new AtomicLong
      val bytesReadCallback = inputMetrics.bytesReadCallback.orElse(Some(() => count.get().toLong))
      inputMetrics.setBytesReadCallback(bytesReadCallback)

      context.addTaskCompletionListener(context => close())
      var havePair = false
      var finished = false

      val outputStream: Try[ByteArrayOutputStream] = Retry(maxRetries + 1) {
        downloadFile(split.asInstanceOf[DownloadRDDPartition[A]].downloadFile)
      }

      var reader: BufferedReader = null

      outputStream match {
        case Success(v) => {
          reader = new BufferedReader(new InputStreamReader(getInputStream(new ByteArrayInputStream(v.toByteArray))))
        }
        case Failure(e) => {
          val msg = s"Error downloading partition[$split] after $maxRetries tries"
          logError(msg)

          throw DownloadException(msg, e)
        }
      }

      override def hasNext: Boolean = {
        if (!finished && !havePair) {
          finished = !reader.ready
          if (finished) {
            close()
          }
          havePair = !finished
        }
        !finished
      }

      override def next(): String = {
        if (!hasNext) {
          throw new java.util.NoSuchElementException("End of stream")
        }
        havePair = false

        val line = reader.readLine()

        if (!finished) {
          inputMetrics.incRecordsRead(1)
          count.addAndGet(line.length)
        }

        line
      }

      private def close() {
        logInfo("Finished partition: " + split)

        if (reader != null) {
          try {
            reader.close()
          } catch {
            case e: Exception =>
              if (!ShutdownHookManager.inShutdown()) {
                logWarning("Exception in RecordReader.close()", e)
              }
          } finally {
            reader = null
          }
          if (bytesReadCallback.isDefined) {
            inputMetrics.updateBytesRead()
          } else {
            inputMetrics.incBytesRead(count.get())
          }
        }
      }
    }
    new InterruptibleIterator(context, iter)
  }

  private def downloadFile(downloadFile: A): ByteArrayOutputStream = {
    logInfo(s"Downloading file[$downloadFile]")

    val downloader = downloaderFactory.get(downloaderParams)

    val outputStream = new ByteArrayOutputStream()

    downloader.downloadFile(downloadFile, outputStream)

    outputStream
  }

  private def getInputStream(inputStream: InputStream): InputStream = {
    try {
      inputStream.mark(GZIP_MAGIC_LENGTH);

      val bytes = Array.ofDim[Byte](GZIP_MAGIC_LENGTH)

      inputStream.read(bytes)

      inputStream.reset()
      if (bytes(0) == GZIPInputStream.GZIP_MAGIC.toByte && bytes(1) == (GZIPInputStream.GZIP_MAGIC >> 8).toByte) {
        new GZIPInputStream(inputStream)
      }
      else {
        inputStream
      }
    } catch {
      case e: Throwable => inputStream
    }
  }

  override def range(): DownloadFileRange = DownloadFileRange(files.flatMap(_.date).sorted.headOption.getOrElse(null), files.map(_.file): _*)

  override protected def getPartitions: Array[Partition] = {
    files.zipWithIndex.map { case (f, i) => new DownloadRDDPartition(f, i) }
  }
}

class DownloadRDDPartition[A <: DownloadFile](
                                               val downloadFile: A,
                                               val index: Int
                                             ) extends Partition {

  override def equals(other: Any): Boolean = other match {
    case that: DownloadRDDPartition[A] =>
      (that canEqual this) &&
        downloadFile == that.downloadFile &&
        index == that.index
    case _ => false
  }

  def canEqual(other: Any): Boolean = other.isInstanceOf[DownloadRDDPartition[A]]

  override def hashCode(): Int = {
    val state = Seq(super.hashCode(), downloadFile, index)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }

  override def toString = s"FileDownloaderRDDPartition($DownloadFile, $index)"
}

trait HasDownloadFileRange {
  def range: DownloadFileRange
}

sealed trait DownloadFileRange

object DownloadFileRange {
  def apply(date: Date, files: String*): DownloadFileRange = {
    new DateFilesDownloadFileRange(date, files)
  }

  def apply(date: Date): DownloadFileRange = {
    new DateDownloadFileRange(date)
  }

  case class DateFilesDownloadFileRange(val date: Date, val files: Seq[String]) extends DownloadFileRange

  case class DateDownloadFileRange(val date: Date) extends DownloadFileRange

}
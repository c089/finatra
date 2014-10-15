package com.twitter.finatra

import com.google.common.base.Objects
import com.twitter.finagle.{Service, SimpleFilter}
import org.jboss.netty.handler.codec.http.HttpResponseStatus._
import org.jboss.netty.buffer.ChannelBuffers.copiedBuffer
import com.twitter.util.Future
import com.twitter.finagle.http.{Request => FinagleRequest, Response => FinagleResponse}

import org.apache.commons.io.{FileUtils, IOUtils}
import java.io._
import javax.activation.MimetypesFileTypeMap
import com.twitter.app.App
import java.util.{TimeZone, Locale, Date}
import org.jboss.netty.handler.codec.http.HttpHeaders
import java.text.SimpleDateFormat

object FileResolver {

  def hasFile(path: String): Boolean = {
    if(config.env() == "production"){
      hasResourceFile(path)
    } else {
      hasLocalFile(path)
    }
  }

  def getInputStream(path: String): InputStream = {
    if(config.env() == "production"){
      getResourceInputStream(path)
    } else {
      try {
        getLocalInputStream(path)
      } catch {
        // some of the resources might be in jar dependencies
        case e: FileNotFoundException => getResourceInputStream(path)
      }
    }
  }

  private def getResourceInputStream(path: String): InputStream = {
    val ins=getClass.getResourceAsStream(path)
    if (ins==null) throw new FileNotFoundException(path + " not found in resources")
    ins
  }


  private def getLocalInputStream(path: String): InputStream = {
    val file = new File(config.docRoot(), path)

    new FileInputStream(file)
  }

  private[finatra] def hasResourceFile(path: String): Boolean = {
    val fi      = getClass.getResourceAsStream(path)
    var result  = false

    try {
      if (fi != null && fi.available > 0) {
        result = true
      } else {
        result = false
      }
    } catch {
      case e: Exception =>
        result = false
    } finally {
      IOUtils.closeQuietly(fi)
    }
    result
  }

  private[finatra] def hasLocalFile(path: String): Boolean = {
    val file = new File(config.docRoot(), path)

    if(file.toString.contains(".."))     return false
    if(!file.exists || file.isDirectory) return false
    if(!file.canRead)                    return false

    true
  }
}

object FileService {

  def getContentType(str: String): String = {
    extMap.getContentType(str)
  }

  def getContentType(file: File): String = {
    extMap.getContentType(file)
  }

  lazy val extMap = new MimetypesFileTypeMap(
    FileService.getClass.getResourceAsStream("/META-INF/mime.types")
  )

}

class FileService extends SimpleFilter[FinagleRequest, FinagleResponse] with App with Logging {

  def isValidPath(path: String): Boolean = {
    FileResolver.hasResourceFile(path)
  }

  def apply(request: FinagleRequest, service: Service[FinagleRequest, FinagleResponse]): Future[FinagleResponse] = {
    val path = new File(config.assetPath(), request.path).toString
    val response = if (config.env() == "production") {
      resourceFileResponse(request, path)
    } else {
      localFileResponse(request, path)
    }
    if (response.isEmpty) {
      service(request)
    } else {
      Future.value(response.get)
    }
  }

  private def resourceFileResponse(request: FinagleRequest, path: String) = {
    var response: Option[FinagleResponse] = None
    val resourceURL = getClass.getResource(path)
    if (request.path != "/" && resourceURL != null) {
      val conn = resourceURL.openConnection
      val stream = conn.getInputStream
      if (stream != null) {
        try {
          val contentType = FileService.getContentType(path)
          val lastModified = new Date(conn.getLastModified)
          response = createResponse(request, contentType, lastModified, () => {
            IOUtils.toByteArray(stream)
          })
        } finally {
          IOUtils.closeQuietly(stream)
        }
      }
    }
    response
  }

  private def localFileResponse(request: FinagleRequest, path: String) = {
    var response: Option[FinagleResponse] = None
    if (request.path != "/" && FileResolver.hasLocalFile(path)) {
      val file = new File(config.docRoot(), path)
      val contentType = FileService.getContentType(path)
      val lastModified = new Date(file.lastModified)
      response = createResponse(request, contentType, lastModified, () => {
        FileUtils.readFileToByteArray(file)
      })
    }
    response
  }

  private def createResponse(request: FinagleRequest, contentType: String, lastModified: Date, getBytes: () => Array[Byte]) = {
    val response = request.response
    if (ifModifiedSince(request, lastModified).getOrElse(true)) {
      val bytes = getBytes()
      response.status = OK
      response.contentLength = bytes.length
      response.lastModified = lastModified
      response.contentType = contentType
      response.setContent(copiedBuffer(bytes))
    } else {
      response.status = NOT_MODIFIED
      response.contentLength = 0
    }
    Some(response)
  }

  private def ifModifiedSince(request: FinagleRequest, lastModified: Date) = {
    Option(request.headers().get(HttpHeaders.Names.IF_MODIFIED_SINCE)).map { value =>
      val format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss ZZZ", Locale.US)
      format.setTimeZone(TimeZone.getTimeZone("UTC"))
      format.parse(value).before(lastModified)
    }
  }
}

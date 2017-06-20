package com.andycot.pricescraper.services

import com.andycot.pricescraper.models.{PriceScraperUrl, PriceScraperWebsite, PriceScraperWebsiteParameter}
import org.mongodb.scala.{MongoClient, MongoCollection, MongoDatabase}
import play.api.Logger

import scala.concurrent.Future
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

/**
  * Created by Francois FERRARI on 10/06/2017
  */
class PriceScraperUrlService {
  val mongoClient: MongoClient = MongoClient()
  val database: MongoDatabase = mongoClient.getDatabase("andycot")

  val collection: MongoCollection[PriceScraperUrl] = database
    .getCollection[PriceScraperUrl]("priceScraperUrls")
    .withCodecRegistry(MongoCodec.getCodecRegistry)

  /**
    *
    * @return
    */
  def findPriceScraperUrls: Future[Seq[PriceScraperUrl]] = collection.find().toFuture()

  /**
    * Generate a list of urls given a base url and the max page number.
    * The base url must contain the "page=..." string
    *
    * @param priceScraperBaseUrl
    * @param maxPageNumber
    * @return
    */
  def generateAllUrls(priceScraperBaseUrl: PriceScraperUrl, priceScraperWebsites: Seq[PriceScraperWebsite], maxPageNumber: Int): List[String] = {
    val priceScraperWebsite: Seq[PriceScraperWebsite] = priceScraperWebsites.filter(_.website == priceScraperBaseUrl.website)

    (1 to maxPageNumber).map(generateUrl(priceScraperBaseUrl, priceScraperWebsite)).toList
  }

  /**
    *
    * @param priceScraperUrl
    * @param pageNumber
    * @return
    */
  def generateUrl(priceScraperUrl: PriceScraperUrl, priceScraperWebsiteParameters: Seq[PriceScraperWebsite])(pageNumber: Int): String = {
    val pageParameter: String = f"page=$pageNumber"
    val parameters: Seq[PriceScraperWebsiteParameter] = priceScraperWebsiteParameters.flatMap(_.defaultUrlParameters) :+ PriceScraperWebsiteParameter(pageParameter, None)

    addUrlParameters(priceScraperUrl.url, toRegex(parameters))
  }

  /**
    *
    * @param parameters
    */
  def toRegex(parameters: Seq[PriceScraperWebsiteParameter]): Seq[(String, Option[Regex])] = {
    parameters.map {
      case PriceScraperWebsiteParameter(toAdd, Some(pattern)) => Try(new Regex(pattern)) match {
        case Success(regex) =>
          (toAdd, Some(regex))

        case Failure(f) =>
          Logger.warn(s"toRegex: Unable to create a regex from string $pattern", f)
          (toAdd, None)
      }

      case p@PriceScraperWebsiteParameter(_, None) =>
        (p.parameter, None)
    }
  }

  /**
    *
    * @param url
    * @param urlParameters
    * @return
    */
  def addUrlParameters(url: String, urlParameters: Seq[(String, Option[Regex])]): String = {
    val separator = if (url.contains("?")) "&" else "?"

    urlParameters.foldLeft((separator, url)) { case ((urlParameterSeparator, newUrl), urlParameter) =>
      addUrlParameter(newUrl, urlParameter._1, urlParameterSeparator, urlParameter._2)
    }._2
  }

  /**
    *
    * @param url
    * @param urlParameter
    * @param urlParameterSeparator
    * @param urlParameterRegex
    * @return
    */
  def addUrlParameter(url: String, urlParameter: String, urlParameterSeparator: String, urlParameterRegex: Option[Regex] = None): (String, String) = {
    urlParameterRegex.fold(addUrlParameterGivenString(url, urlParameter, urlParameterSeparator))(addUrlParameterGivenRegex(url, urlParameter, urlParameterSeparator, _))
  }

  /**
    *
    * @param url
    * @param urlParameter
    * @param urlParameterSeparator
    * @return
    */
  def addUrlParameterGivenString(url: String, urlParameter: String, urlParameterSeparator: String): (String, String) = {
    url.contains(urlParameter) match {
      case true =>
        (urlParameterSeparator, url)
      case false =>
        ("&", s"$url$urlParameterSeparator$urlParameter")
    }
  }

  /**
    *
    * @param url
    * @param urlParameter
    * @param urlParameterSeparator
    * @param urlParameterRegex
    * @return
    */
  def addUrlParameterGivenRegex(url: String, urlParameter: String, urlParameterSeparator: String, urlParameterRegex: Regex): (String, String) = {
    ("&", urlParameterRegex.findFirstIn(url).fold(s"$url$urlParameterSeparator$urlParameter")(_ => urlParameterRegex.replaceAllIn(url, urlParameter)))
  }
}

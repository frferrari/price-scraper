package com.andycot.pricescraper.business

import akka.http.scaladsl.model.Uri
import com.andycot.pricescraper.models._
import com.andycot.pricescraper.services.PriceScraperUrlService

import scala.concurrent.Future
import scala.util.Try

/**
  * Created by Francois FERRARI on 12/06/2017
  */
trait PriceScraperExtractor {
  def extractAuctions(priceScraperWebsite: PriceScraperWebsite, uri: Uri, htmlContent: String): Future[Seq[PriceScraperAuction]]

  def getPagedUrls(priceCrawlerUrl: PriceScraperUrl, priceCrawlerWebsites: Seq[PriceScraperWebsite], htmlContent: String): Seq[PriceScraperUrl]

  def getItemPrice(priceWithCurrency: String): Try[PriceScraperItemPrice]

  def mapToInternalCurrency(externalCurrency: String): Option[String]
//
//  def absoluteUrl(priceScraperWebsite: PriceScraperWebsite, url: String): String = {
//    println(s"===== ${priceScraperWebsite.baseUrl} url $url")
//
//    if (url.startsWith("http") || url.startsWith("www.")) {
//      url
//    } else {
//      val baseUrl = if (priceScraperWebsite.baseUrl.endsWith("/"))
//        priceScraperWebsite.baseUrl.dropRight(1)
//      else
//        priceScraperWebsite.baseUrl
//
//      if (url.startsWith("/"))
//        s"${priceScraperWebsite.baseUrl}$url"
//      else
//        s"${priceScraperWebsite.baseUrl}/$url"
//    }
//  }
}

package com.andycot.pricescraper.utils

import akka.http.scaladsl.model.Uri.Query
import com.andycot.pricescraper.models.{PriceScraperQueryParameter, PriceScraperUrl, PriceScraperWebsite}

/**
  * Created by Francois FERRARI on 26/06/2017
  */
object PriceScraperUrlManager {
  /**
    * Generate a list of urls given a base url and the max page number.
    *
    * @param priceScraperUrl
    * @param priceScraperWebsites
    * @param maxPageNumber
    * @return
    */
  def generateAllUrls(priceScraperUrl: PriceScraperUrl, priceScraperWebsites: Seq[PriceScraperWebsite], maxPageNumber: Int): Seq[PriceScraperUrl] = {
    priceScraperWebsites.find(_.website == priceScraperUrl.website).map { priceScraperWebsite =>
      (1 to maxPageNumber).map(generateUrl(priceScraperUrl, priceScraperWebsite.defaultQueryParameters)).toList
    }.getOrElse(Nil)
  }

  /**
    *
    * @param priceScraperUrl
    * @param defaultQueryParameters
    * @param pageNumber
    * @return
    */
  def generateUrl(priceScraperUrl: PriceScraperUrl, defaultQueryParameters: Seq[PriceScraperQueryParameter])(pageNumber: Int = 1): PriceScraperUrl = {
    val queryParameters = defaultQueryParameters.map(dqp => dqp.name -> dqp.value) :+ ("page", pageNumber.toString)

    priceScraperUrl.copy(uri = priceScraperUrl.uri.withQuery(Query(queryParameters:_*)))
  }
}

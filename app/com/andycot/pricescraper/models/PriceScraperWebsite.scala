package com.andycot.pricescraper.models

import java.time.Instant

import akka.http.scaladsl.model.Uri

/**
  * Created by Francois FERRARI on 12/06/2017
  */
case class PriceScraperWebsite(website: String,
                               baseUrl: String,
                               canSortByAuctionEndDate: Boolean,
                               defaultQueryParameters: Seq[PriceScraperQueryParameter],
                               created_at: Instant = Instant.now()
                              )

object PriceScraperWebsite {
  val DCP = "DCP"
}

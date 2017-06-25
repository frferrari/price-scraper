package com.andycot.pricescraper.models

import java.time.Instant

import org.bson.codecs.configuration.CodecProvider
import org.mongodb.scala.bson.codecs.Macros

/**
  * Created by Francois FERRARI on 12/06/2017
  */
case class PriceScraperWebsite(website: String,
                               baseUrl: String,
                               canSortByAuctionEndDate: Boolean,
                               defaultUrlParameters: Seq[PriceScraperWebsiteParameter],
                               created_at: Instant = Instant.now()
                              )

object PriceScraperWebsite {
  val DCP = "DCP"
}

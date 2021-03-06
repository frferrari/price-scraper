package com.andycot.pricescraper.models

import akka.http.scaladsl.model.Uri

/**
  * Created by Francois FERRARI on 10/06/2017
  */
case class PriceScraperUrl(website: String,
                           url: String,
                           familyId: Int,
                           areaId: Option[Int],
                           topicId: Option[Int],
                           yearRange: Option[PriceScraperYearRange],
                           defaultOptions: Seq[PriceScraperOption]
                          )

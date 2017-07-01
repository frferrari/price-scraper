package com.andycot.pricescraper.models


/**
  * Created by Francois FERRARI on 15/06/2017
  */
case class PriceScraperUrlRelations(priceScraperUrl: PriceScraperUrl,
                                    priceScraperUrlContents: Seq[PriceScraperUrlContent]
                                   )

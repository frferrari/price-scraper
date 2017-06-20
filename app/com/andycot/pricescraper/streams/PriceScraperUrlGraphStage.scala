package com.andycot.pricescraper.streams

import java.time.Instant
import javax.inject.Inject

import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler}
import akka.stream.{Attributes, Outlet, SourceShape}
import com.andycot.pricescraper.models.PriceScraperUrl
import com.andycot.pricescraper.services.PriceScraperUrlService
import play.api.Logger

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

/**
  * Created by Francois FERRARI on 10/06/2017
  *
  * This code allows to produce an infinite stream of URLs to grab prices from.
  * We read the url list from a database. The url list is not supposed to change frequently
  * anyway an update mechanism is in place.
  *
  * Stopping the infinite stream could be done by adding a call to a service that would return
  * a flag to tell if a stop if requested (code to be provided)
  *
  * Some help about how to use getAsyncCallback was found here:
  * http://doc.akka.io/docs/akka/current/scala/stream/stream-customize.html
  * http://doc.akka.io/docs/akka/current/scala/stream/stream-customize.html#custom-processing-with-graphstage
  *
  */
class PriceScraperUrlGraphStage @Inject()(implicit priceScraperUrlService: PriceScraperUrlService, ec: ExecutionContext)
  extends GraphStage[SourceShape[PriceScraperUrl]] {

  val elapsedSecondsBetweenUpdates = 300

  val out: Outlet[PriceScraperUrl] = Outlet("PriceCrawlerUrlGraphStage")
  override val shape: SourceShape[PriceScraperUrl] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private var priceScraperUrls = Seq.empty[PriceScraperUrl]
    private var index = -1
    private var lastUpdate = Instant.now().minusSeconds(elapsedSecondsBetweenUpdates * 2)

    /*
     * http://tech.measurence.com/2016/06/01/a-dive-into-akka-streams.html
     * https://groups.google.com/forum/#!msg/akka-user/fBkWg4gSwEI/uiC2j1U7AAAJ;context-place=msg/akka-user/XQo2G7_mTcQ/8JKrM_TnDgAJ
     */
    private def safePushCB(withUpdate: Boolean) = getAsyncCallback[Try[Seq[PriceScraperUrl]]] {
      case Success(urls) =>
        if (withUpdate) {
          lastUpdate = Instant.now
          priceScraperUrls = urls
        }
        pushNextUrl

      case Failure(f) =>
        Logger.error(s"Enable to update the priceScraperUrls", f)
        completeStage()
    }

    private def pushNextUrl = {
      val nextUrl = getNextUrl(priceScraperUrls)
      push(out, nextUrl)
    }

    private def getNextUrl(urls: Seq[PriceScraperUrl]) = {
      index = if (index >= urls.length - 1) 0 else index + 1
      val nextUrl = urls(index)
      nextUrl
    }

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        // Check if an update of the url list is needed
        if (Instant.now.getEpochSecond - lastUpdate.getEpochSecond > elapsedSecondsBetweenUpdates) {
          priceScraperUrlService.findPriceScraperUrls.onComplete(safePushCB(true).invoke)
        } else {
          // Not optimal: Future.fromTry(Try(priceScraperUrls)).onComplete(safePushCB(false).invoke)
          pushNextUrl
        }
      }
    })
  }
}

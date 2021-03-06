/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.alpakka.googlecloud.bigquery.impl.parser

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.alpakka.googlecloud.bigquery.impl.parser.Parser.PagingInfo
import akka.stream.alpakka.googlecloud.bigquery.scaladsl.SprayJsonSupport._
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.ClosedShape
import akka.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers
import spray.json.{JsObject, JsValue}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class ParserSpec extends TestKit(ActorSystem("ParserSpec")) with AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  implicit val ec: ExecutionContext = system.dispatcher

  override def afterAll: Unit =
    TestKit.shutdownActorSystem(system)

  val pageToken = "dummyToken"
  val jobId = "dummyJobId"
  val response = HttpResponse(
    entity = HttpEntity(ContentTypes.`application/json`,
                        s"""{"pageToken": "$pageToken", "jobReference": { "jobId": "$jobId" } }""")
  )
  val responseWithoutJobId = HttpResponse(
    entity = HttpEntity(ContentTypes.`application/json`, s"""{"pageToken": "$pageToken"}""")
  )
  val responseWhereJobIncomplete = HttpResponse(
    entity = HttpEntity(ContentTypes.`application/json`,
                        s"""{"pageToken": "$pageToken", "jobReference": { "jobId": "$jobId" }, "jobComplete": false}""")
  )

  def createTestGraph[Data, S1, S2](
      source: Source[HttpResponse, _],
      dataSink: Sink[Data, S1],
      pageSink: Sink[(Boolean, PagingInfo), S2]
  )(implicit unmarshaller: Unmarshaller[JsValue, Data]): RunnableGraph[(S1, S2)] =
    RunnableGraph.fromGraph(GraphDSL.create(dataSink, pageSink)((_, _)) { implicit builder => (s1, s2) =>
      import GraphDSL.Implicits._

      val parser = builder.add(Parser[JsValue, Data])

      source ~> parser.in
      parser.out0 ~> s1
      parser.out1 ~> s2

      ClosedShape
    })

  "Parser" should {

    "output the value returned by the parse function" in {
      implicit val unmarshaller = Unmarshaller.strict((_: JsValue) => pageToken)
      val testGraph =
        createTestGraph(Source.single(response), TestSink.probe[String], TestSink.probe[(Boolean, PagingInfo)])

      val (dataSink, pageSink) = testGraph.run()

      Future(pageSink.requestNext())

      val result = dataSink.requestNext(3.seconds)

      result should be(pageToken)
    }

    "output the page token and jobid parsed from the http response" in {
      val testGraph =
        createTestGraph(Source.single(response), TestSink.probe[JsValue], TestSink.probe[(Boolean, PagingInfo)])

      val (dataSink, pageSink) = testGraph.run()

      Future(dataSink.requestNext())

      val pagingInfo = pageSink.requestNext(3.seconds)

      pagingInfo should be((false, PagingInfo(Some(pageToken), Some(jobId))))
    }

    "output the page token parsed from the http response" in {
      implicit val unmarshaller = Unmarshaller.strict((x: JsValue) => x.asJsObject)
      val testGraph = createTestGraph(Source.single(responseWithoutJobId),
                                      TestSink.probe[JsObject],
                                      TestSink.probe[(Boolean, PagingInfo)])

      val (dataSink, pageSink) = testGraph.run()

      Future(dataSink.requestNext())

      val pagingInfo = pageSink.requestNext(3.seconds)

      pagingInfo should be((false, PagingInfo(Some(pageToken), None)))
    }

    "output none for page token when http response does not contain page token" in {
      val testGraph =
        createTestGraph(Source.single(HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, "{}"))),
                        TestSink.probe[JsValue],
                        TestSink.probe[(Boolean, PagingInfo)])

      val (dataSink, pageSink) = testGraph.run()

      Future(dataSink.requestNext())

      val pagingInfo = pageSink.requestNext(3.seconds)

      pagingInfo should be((false, PagingInfo(None, None)))
    }

    "retry when job is not complete" in {
      val testGraph =
        createTestGraph(Source.single(responseWhereJobIncomplete),
                        TestSink.probe[JsValue],
                        TestSink.probe[(Boolean, PagingInfo)])

      val (dataSink, pageSink) = testGraph.run()

      Future(dataSink.requestNext())

      val result = pageSink.requestNext(3.seconds)

      result should be((true, PagingInfo(Some(pageToken), Some(jobId))))
    }

  }
}

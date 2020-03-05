/*
 * Copyright 2020 Daniel Sobral
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sbttrickle.github

/*
import cats.effect.{ContextShift, IO}
import cats.effect.IO.contextShift

import scala.concurrent.ExecutionContext.Implicits.global
import github4s.{Github, GithubResponses}
import github4s.GithubIOSyntax._
import github4s.domain.{Pagination, PRFilterOpen}

object PullRequests {
  implicit val IOContextShift: ContextShift[IO] = IO.contextShift(global)
  def stuff(): Unit = {
    val filter = List(PRFilterOpen)
    val u1 = Github[IO](sys.env.get("GITHUB_TOKEN")).pullRequests.listPullRequests("dcsobral", "sbt-trickle", filter).toId
    u1.foreach { res =>
      res.result.foreach(println)
    }
  }
  // Clone repository ???  --V
  // Run bump version script
    // Checkout PR branch
    // Change versions
    // Commit
    // Push
  // Create Pull Request ???

  // PR branch name
  // PR message
  // PR user name
  // PR user email
  // PR body
}
*/

/* 
 Copyright (C) GridGain Systems. All Rights Reserved.
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

/*
 * ___    _________________________ ________
 * __ |  / /____  _/__  ___/__  __ \___  __ \
 * __ | / /  __  /  _____ \ _  / / /__  /_/ /
 * __ |/ /  __/ /   ____/ / / /_/ / _  _, _/
 * _____/   /___/   /____/  \____/  /_/ |_|
 *
 */

package org.gridgain.visor.commands.deploy

import org.scalatest._
import matchers._
import org.gridgain.visor._
import VisorDeployCommand._

/**
 * Unit test for 'deploy' command.
 *
 * @author @java.author
 * @version @java.version
 */
class VisorDeployCommandSpec extends FlatSpec with ShouldMatchers {
    behavior of "A 'deploy' visor command"

    it should "copy folder" in {
        visor.deploy("-h=uname:passwd@localhost -s=/home/uname/test -d=dir")
    }
}

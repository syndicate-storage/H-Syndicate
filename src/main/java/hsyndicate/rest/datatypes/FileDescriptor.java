/*
   Copyright 2016 The Trustees of University of Arizona

   Licensed under the Apache License, Version 2.0 (the "License" );
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package hsyndicate.rest.datatypes;

import org.codehaus.jackson.annotate.JsonProperty;

public class FileDescriptor {
    
    private long fd;

    public FileDescriptor() {
        this.fd = 0;
    }

    @JsonProperty("fd")
    public long getFd() {
        return this.fd;
    }

    @JsonProperty("fd")
    public void setFd(long fd) {
        this.fd = fd;
    }
}

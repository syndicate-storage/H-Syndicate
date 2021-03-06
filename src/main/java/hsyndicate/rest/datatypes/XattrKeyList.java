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

import java.util.ArrayList;
import java.util.List;
import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonProperty;

/**
 *
 * @author iychoi
 */
public class XattrKeyList {
    private List<String> keys = new ArrayList<String>();

    public XattrKeyList() {
        
    }
    
    @JsonProperty("keys")
    public List<String> getKeys() {
        return this.keys;
    }

    @JsonProperty("keys")
    public void addKeys(List<String> keys) {
        this.keys.addAll(keys);
    }
    
    @JsonIgnore
    public void addKey(String key) {
        this.keys.add(key);
    }
}

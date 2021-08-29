# kotlin_curl_dsl

## usage

since is just a single file, so just copy curl.kt into your project and make sure you have relevant dependencies

## example

### json dsl

```
          val json = json {
            "arrs" to jsonArr {
                for( i in 1..3){
                    + i.toString()
                }
                + json{
                    "key4" to "value4"
                }
            }
            "key1" to "value1"
            "key2" to json {
                "key3" to "value3"
            }
        }.toString()
        println(json)

```

out put like this:

```
{
  "key1": "value1",
  "key2": {
    "key3": "value3"
  },
  "arrs": [
    "1",
    "2",
    "3",
    {
      "key4": "value4"
    }
  ]
}
```

### simple one

```
     val response = curl {
            request {
                method("get") //if the method not specified,it will be get
                url("some_url_for_get")
                params {  //params will append on url 'some_url_for_get?name=syu&pwd=pwd'
                    "name" to "syu"
                    "pwd" to "pwd"
                }
            }
        }as Response
        val result = response.body()?.toString()
        println(result)
```

### POST request with given result type

```
        val map = curl {
            request {
                method("post")
                url("url_for_post")
                /**
                 *  in this body function,you can return org.json.JSONObject,org.json.JSONArray
                 *  String,InputStream or File(for upload),and any classes implements okhttp3.RequestBody
                 */
                body{
                    json {
                        "name" to "syu"
                        "pwd" to "kknd"
                    }
                }
            }
            returnType(object : TypeReference<Map<String, String>>() {})
            //you can also get certain java classes like this:
            // returnType(BaseUser::class.java)
        } as Map<String,String> //make sure this type same with returnType
        map.forEach{ k,v ->
            println(k+"  "+v)
        }
    }
```

### watch

```
curl {
    client { k8sOkhttpClient }  // you can specify which OkHttpClient you want to use
    request {
        url("https://127.0.0.1:6335/api/v1/pods")
        params {
            "watch" to "true"
        }
    }
    event {
        threadPool(Executors.newFixedThreadPool(2)) //the onWacth function will run in this thread-pool
        onWacth { line ->
            //line is String
            println(line)
        }
    }
}
```


### Docker:

Login as root to a running container, without knowing the root password:

    s/d exec -u 0 search bash    # user id 0 = root

    # or with docker (not docker-compose):
    docker exec -u 0 -it container_name bash

Free up disk space, by deleting old images:

    docker image prune  # optionally, add:  --all

How to push images to a local repo, to test in Vagrant: see [testing-images-in-vagrant.md](./testing-images-in-vagrant.md).


### ElasticSearch stuff:

List indexes:  
http://localhost:9200/_aliases

List everything:  
http://localhost:9200/_search?pretty&size=9999

List posts in site 3:  
http://localhost:9200/all_english_v1/post/_search?pretty&routing=3&size=9999

Search:  
http://localhost:9200/_search?pretty&q=approvedText:zzwwqq2

Status of everything:  
http://localhost:9200/_cat?v

Request body search:  

```
$ curl -XGET 'http://localhost:9200/_search' -d '{
    "query" : {
        "term" : { "approvedText" : "something" }
    }
}
```

Reindex everything: (might take long: minutes/hours/weeks, depending on db size)

```
curl -XDELETE 'http://localhost:9200/all_english_v1/'
docker-compose restart web app
```

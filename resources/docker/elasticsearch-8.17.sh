docker run -d \
    --name elasticsearch \
    -p 9200:9200 \
    -e "discovery.type=single-node" \
    -e "xpack.security.enabled=false" \
    -e "ES_JAVA_OPTS=-Xms512m -Xmx512m" \
    -v es_data:/usr/share/elasticsearch/data \
    elasticsearch:8.17.0 \
    bash -c "[ -d plugins/analysis-ik ] || bin/elasticsearch-plugin install --batch https://get.infini.cloud/elasticsearch/analysis-ik/8.17.0; bin/elasticsearch"

# Elasticsearch 本地启动脚本
#
# 【版本约束】ES 服务端的大版本必须与项目依赖的 Java 客户端一致
#
#   rag/pom.xml 依赖 co.elastic.clients:elasticsearch-java，版本由 Spring Boot BOM 管理：
#   Spring Boot 4.1.0 → elasticsearch-java 9.4.2
#
#   所以服务端必须是 9.x，不能用 8.x，原因有两条：
#     1. 客户端 9.x 会带 compatible-with=9 请求头，8.x 服务端不识别 → 返回 400
#        表现为后端启动失败：TransportException: status: 400, [es/indices.exists]
#     2. EsClientConfig 使用了 9.x 独有的 co.elastic.clients.transport.rest5_client 包，
#        把客户端降级到 8.x 会直接编译不过
#
#   升级 Spring Boot 时需同步检查此处的 ES 版本。
#
# 【IK 插件】配置里 analyzer=ik_max_word / search-analyzer=ik_smart 依赖中文分词插件，
#   官方镜像不含，故首次启动时自动安装；插件版本必须与 ES 版本严格一致。

docker run -d \
    --name elasticsearch \
    -p 9200:9200 \
    -e "discovery.type=single-node" \
    -e "xpack.security.enabled=false" \
    -e "ES_JAVA_OPTS=-Xms512m -Xmx512m" \
    -v es_data:/usr/share/elasticsearch/data \
    elasticsearch:9.2.0 \
    bash -c "[ -d plugins/analysis-ik ] || bin/elasticsearch-plugin install --batch https://get.infini.cloud/elasticsearch/analysis-ik/9.2.0; bin/elasticsearch"

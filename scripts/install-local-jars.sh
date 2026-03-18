#!/bin/bash
# 将项目 lib 下的本地 JAR 安装到 ~/.m2/repository，便于 IDEA 与 Maven 解析。
# 使用：在项目根目录执行 bash scripts/install-local-jars.sh
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

install_spark() {
  [ -f core/lib/spark-core-2.6.0.jar ] || return 0
  mvn install:install-file -q -Dfile=core/lib/spark-core-2.6.0.jar \
    -DgroupId=com.sparkframework -DartifactId=spark-core -Dversion=2.6.0 -Dpackaging=jar
  echo "Installed com.sparkframework:spark-core:2.6.0"
}

install_aqmd() {
  [ -f market/lib/aqmd-netty-2.0.1.jar ] || return 0
  mvn install:install-file -q -Dfile=market/lib/aqmd-netty-2.0.1.jar \
    -DgroupId=com.aqmd -DartifactId=aqmd-netty -Dversion=2.0.1 -Dpackaging=jar -DgeneratePom=true
  mvn install:install-file -q -Dfile=market/lib/aqmd-netty-api-2.0.1.jar \
    -DgroupId=com.aqmd -DartifactId=aqmd-netty-api -Dversion=2.0.1 -Dpackaging=jar -DgeneratePom=true
  mvn install:install-file -q -Dfile=market/lib/aqmd-netty-core-2.0.1.jar \
    -DgroupId=com.aqmd -DartifactId=aqmd-netty-core -Dversion=2.0.1 -Dpackaging=jar -DgeneratePom=true
  echo "Installed com.aqmd:aqmd-netty*:2.0.1 (3 jars)"
}

install_spark
install_aqmd
echo "Done."

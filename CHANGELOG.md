## [1.2.0](https://github.com/arkhn/clickhouse-jdbc-bridge/compare/v1.1.0...v1.2.0) (2026-07-12)


### Bug Fixes

* **bridge:** run /columns_info on a worker thread, not the event loop ([e796c73](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/e796c732911ae208937162079ace0de231a80e77))
* **oracle:** date convertion ([b966241](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/b96624177f5448058e02ce3c2cdc177d9cdc4c9f))
* **timestamp:** always convert to datetime64 ([8fbd91c](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/8fbd91c5a1f06047a886e56dfed9a22fd684e9f0))


### Features

* add failing test for datetime conversion ([ee3e436](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/ee3e43677719c9bb52c14a98b304ae4e4bf05a05))
* add integration test ([55c701f](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/55c701f8d8327d412c15a4f6ecb4107e9fdacb44))
* add POST /test to check a datasource before it is saved ([57b5fc6](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/57b5fc693cef40b491eec035f2e0ea04db23995f))
* honor an inline per-datasource caCertificate for TLS trust ([c8a80ec](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/c8a80ec1c81057116d0043c0667802a78787d38a))
* **jdbc:** make fetch_size configurable per datasource and propagate it correctly ([f3d352a](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/f3d352a74478c936c77440d3dc2207def00506e8))

## [1.2.0-rc.3](https://github.com/arkhn/clickhouse-jdbc-bridge/compare/v1.2.0-rc.2...v1.2.0-rc.3) (2026-07-12)


### Bug Fixes

* **bridge:** run /columns_info on a worker thread, not the event loop ([e796c73](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/e796c732911ae208937162079ace0de231a80e77))


### Features

* add POST /test to check a datasource before it is saved ([57b5fc6](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/57b5fc693cef40b491eec035f2e0ea04db23995f))
* honor an inline per-datasource caCertificate for TLS trust ([c8a80ec](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/c8a80ec1c81057116d0043c0667802a78787d38a))

## [1.2.0-rc.2](https://github.com/arkhn/clickhouse-jdbc-bridge/compare/v1.2.0-rc.1...v1.2.0-rc.2) (2026-07-12)


### Bug Fixes

* **oracle:** date convertion ([b966241](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/b96624177f5448058e02ce3c2cdc177d9cdc4c9f))
* **timestamp:** always convert to datetime64 ([8fbd91c](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/8fbd91c5a1f06047a886e56dfed9a22fd684e9f0))


### Features

* add failing test for datetime conversion ([ee3e436](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/ee3e43677719c9bb52c14a98b304ae4e4bf05a05))
* add integration test ([55c701f](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/55c701f8d8327d412c15a4f6ecb4107e9fdacb44))

## [1.2.0-rc.1](https://github.com/arkhn/clickhouse-jdbc-bridge/compare/v1.1.0...v1.2.0-rc.1) (2026-07-12)


### Features

* **jdbc:** make fetch_size configurable per datasource and propagate it correctly ([f3d352a](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/f3d352a74478c936c77440d3dc2207def00506e8))

## [1.1.0](https://github.com/arkhn/clickhouse-jdbc-bridge/compare/v1.0.4...v1.1.0) (2026-05-26)

### Bug Fixes

* add intersystem jdbc driver to full packaging ([08bc0fe](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/08bc0feb543044f5217e21ba38dd40aad2c65c61))
* **buffer:** throw on writeBigInteger overflow instead of silent corruption ([6f95fb8](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/6f95fb80fac49cba9d3fb4bf3f90f86ce2d5e83a))
* documentation ([0f63be7](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/0f63be7a15333f0d549a7e063dad5eaf13972658))
* **it:** extend testBridgeQueryReturnsBytes retry to empty-body case ([e74228c](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/e74228ca60ea4233db5f8de2223ab5b8c151dddc))
* **it:** fork per IT class + use Oracle's default JDBC-handshake wait ([baafdb8](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/baafdb89b4cb6eba0ca2a94e52d4bddfa98e84d8))
* **it:** keep PostgresIT smoke schema to basic types ([c458eeb](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/c458eebe0fb896fa94e4333eec97e63cf793139b)), closes [#5](https://github.com/arkhn/clickhouse-jdbc-bridge/issues/5)
* **it:** require 2 consecutive successful warmup probes ([1346487](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/1346487e505a68236c9c9b9a22d1b6c9aa25d172)), closes [#5](https://github.com/arkhn/clickhouse-jdbc-bridge/issues/5)
* **it:** retry-once on cold-call flake for /ping, /identifier_quote, /schema_allowed ([bd13794](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/bd137947cf628e6266ce98a6aed7caad604c2c90))
* **it:** retry-once on cold-call flake in error-path columns_info test ([82c2ac3](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/82c2ac32af216d2e1e455c00fdc43517da30fa75))
* **it:** warm up the datasource before tests + remove Oracle commit() ([fdceef4](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/fdceef407d7259200110ad9df2a67fe088cb13e1))
* **it:** warmup must check 200 status, not just non-empty body ([9234537](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/923453756344355f58807eed2e9cd498c8c9a33d))
* **it:** widen bridge POST timeouts + retry first MsSqlIT smoke once ([3c411d4](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/3c411d4fe64f0cdebc1999026d5a87b96b63d448))
* **jdbc:** apply Oracle EngineDefaults to the legacy driver class too ([0bdf3ca](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/0bdf3ca39445c8c7f9609160c802577afa8ec009))
* **jdbc:** serialize HikariCP init to remove thread-safety FIXME ([120a6fa](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/120a6facfc20dae4222298f98fee82696b5e82ff))
* **jdbc:** stop hardcoding connectionTestQuery=SELECT 1 for every datasource ([c80479e](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/c80479e5eb1575c6dc01a0c82f76599fda75610b))
* **jmh:** use full Apache 2.0 license header on ByteBufferBench ([328f513](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/328f513ad230b945eb231676b1224f0c245a4be8))
* **parser:** handle dotted schema names in extractSchemaName ([002210b](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/002210bb62e628cf890c3bdaf28f2624c40d264b))
* **reader:** correct OFFSET off-by-one in DataTableReader.process ([ec983cd](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/ec983cd90bc875959b53e440a7830b548cee0367))
* **streaming:** enforce write-queue backpressure in DataTableReader ([67ff93a](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/67ff93a0808493d724d1163e48d343f3e5591d83))
* **types:** map SQL Server DATETIMEOFFSET + Oracle BINARY_FLOAT/DOUBLE ([cb73d94](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/cb73d94ce2e6b9e8fd612caa55b4fe202e69c392))
* **verticle:** unknown datasource returns 404, not 500 ([72aef01](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/72aef011bf9ac07df5982aef98cb8f60c9c96470))

### Features

* **bench:** add end-to-end perf benchmark suite under misc/bench ([aa5dc74](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/aa5dc74f9233f87c24a3777484ca11bc22d8d21b))
* **bench:** add HikariCP observability panels to Grafana dashboard ([132ef4b](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/132ef4bb2b15121688e9952942f56a6091f31703))
* **jdbc:** per-driver connectionTestQuery defaults + 404 for unknown datasource ([5f3fbad](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/5f3fbadea75ef4baa0518c43937e97732bbaeb20))
* **jdbc:** per-driver engine defaults applied at datasource load ([14b4336](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/14b43369a2c44f4c0d978a0e3b5e7760970043d9))
* remove features impact badly security posture ([6018052](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/6018052042dd07313031833719bf542e0117304a))
* **security:** reject adhoc JDBC URLs in inbound requests by default ([ea7f679](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/ea7f679ada125ec6a768ce4169e8b8a27e284fbf))

### Performance Improvements

* **streaming:** hoist nullability + adaptive ByteBuffer size hint ([04d56c9](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/04d56c95b9995d67d03d1e5b657a9256b87df8c0)), closes [#1](https://github.com/arkhn/clickhouse-jdbc-bridge/issues/1)
* **streaming:** raise default batch_size to 4096 and fetch_size to 16384 ([a2fa4ae](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/a2fa4ae8882868eaf2c3bc5fc37ad40477dc6505))

## [1.1.0-rc.11](https://github.com/arkhn/clickhouse-jdbc-bridge/compare/v1.1.0-rc.10...v1.1.0-rc.11) (2026-05-22)

### Bug Fixes

* add intersystem jdbc driver to full packaging ([08bc0fe](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/08bc0feb543044f5217e21ba38dd40aad2c65c61))

## [1.1.0-rc.10](https://github.com/arkhn/clickhouse-jdbc-bridge/compare/v1.1.0-rc.9...v1.1.0-rc.10) (2026-05-19)

### Bug Fixes

* **it:** extend testBridgeQueryReturnsBytes retry to empty-body case ([e74228c](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/e74228ca60ea4233db5f8de2223ab5b8c151dddc))
* **it:** retry-once on cold-call flake for /ping, /identifier_quote, /schema_allowed ([bd13794](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/bd137947cf628e6266ce98a6aed7caad604c2c90))
* **it:** retry-once on cold-call flake in error-path columns_info test ([82c2ac3](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/82c2ac32af216d2e1e455c00fdc43517da30fa75))
* **verticle:** unknown datasource returns 404, not 500 ([72aef01](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/72aef011bf9ac07df5982aef98cb8f60c9c96470))

## [1.1.0-rc.9](https://github.com/arkhn/clickhouse-jdbc-bridge/compare/v1.1.0-rc.8...v1.1.0-rc.9) (2026-05-18)

### Bug Fixes

* **it:** widen bridge POST timeouts + retry first MsSqlIT smoke once ([3c411d4](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/3c411d4fe64f0cdebc1999026d5a87b96b63d448))

## [1.1.0-rc.8](https://github.com/arkhn/clickhouse-jdbc-bridge/compare/v1.1.0-rc.7...v1.1.0-rc.8) (2026-05-18)

### Bug Fixes

* **it:** keep PostgresIT smoke schema to basic types ([c458eeb](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/c458eebe0fb896fa94e4333eec97e63cf793139b)), closes [#5](https://github.com/arkhn/clickhouse-jdbc-bridge/issues/5)
* **it:** require 2 consecutive successful warmup probes ([1346487](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/1346487e505a68236c9c9b9a22d1b6c9aa25d172)), closes [#5](https://github.com/arkhn/clickhouse-jdbc-bridge/issues/5)
* **jdbc:** stop hardcoding connectionTestQuery=SELECT 1 for every datasource ([c80479e](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/c80479e5eb1575c6dc01a0c82f76599fda75610b))

## [1.1.0-rc.7](https://github.com/arkhn/clickhouse-jdbc-bridge/compare/v1.1.0-rc.6...v1.1.0-rc.7) (2026-05-18)

### Bug Fixes

* **jdbc:** apply Oracle EngineDefaults to the legacy driver class too ([0bdf3ca](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/0bdf3ca39445c8c7f9609160c802577afa8ec009))

## [1.1.0-rc.6](https://github.com/arkhn/clickhouse-jdbc-bridge/compare/v1.1.0-rc.5...v1.1.0-rc.6) (2026-05-18)

### Bug Fixes

* **it:** warmup must check 200 status, not just non-empty body ([9234537](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/923453756344355f58807eed2e9cd498c8c9a33d))

### Features

* **jdbc:** per-driver connectionTestQuery defaults + 404 for unknown datasource ([5f3fbad](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/5f3fbadea75ef4baa0518c43937e97732bbaeb20))

## [1.1.0-rc.5](https://github.com/arkhn/clickhouse-jdbc-bridge/compare/v1.1.0-rc.4...v1.1.0-rc.5) (2026-05-18)

### Bug Fixes

* **it:** warm up the datasource before tests + remove Oracle commit() ([fdceef4](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/fdceef407d7259200110ad9df2a67fe088cb13e1))

## [1.1.0-rc.4](https://github.com/arkhn/clickhouse-jdbc-bridge/compare/v1.1.0-rc.3...v1.1.0-rc.4) (2026-05-18)

### Bug Fixes

* **it:** fork per IT class + use Oracle's default JDBC-handshake wait ([baafdb8](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/baafdb89b4cb6eba0ca2a94e52d4bddfa98e84d8))

## [1.1.0-rc.3](https://github.com/arkhn/clickhouse-jdbc-bridge/compare/v1.1.0-rc.2...v1.1.0-rc.3) (2026-05-18)

### Bug Fixes

* **buffer:** throw on writeBigInteger overflow instead of silent corruption ([6f95fb8](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/6f95fb80fac49cba9d3fb4bf3f90f86ce2d5e83a))
* **jdbc:** serialize HikariCP init to remove thread-safety FIXME ([120a6fa](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/120a6facfc20dae4222298f98fee82696b5e82ff))
* **parser:** handle dotted schema names in extractSchemaName ([002210b](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/002210bb62e628cf890c3bdaf28f2624c40d264b))
* **reader:** correct OFFSET off-by-one in DataTableReader.process ([ec983cd](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/ec983cd90bc875959b53e440a7830b548cee0367))

### Features

* **security:** reject adhoc JDBC URLs in inbound requests by default ([ea7f679](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/ea7f679ada125ec6a768ce4169e8b8a27e284fbf))

## [1.1.0-rc.2](https://github.com/arkhn/clickhouse-jdbc-bridge/compare/v1.1.0-rc.1...v1.1.0-rc.2) (2026-05-18)

### Bug Fixes

* documentation ([0f63be7](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/0f63be7a15333f0d549a7e063dad5eaf13972658))

### Features

* remove features impact badly security posture ([6018052](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/6018052042dd07313031833719bf542e0117304a))

## [1.1.0-rc.1](https://github.com/arkhn/clickhouse-jdbc-bridge/compare/v1.0.4...v1.1.0-rc.1) (2026-05-18)

### Bug Fixes

* **jmh:** use full Apache 2.0 license header on ByteBufferBench ([328f513](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/328f513ad230b945eb231676b1224f0c245a4be8))
* **streaming:** enforce write-queue backpressure in DataTableReader ([67ff93a](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/67ff93a0808493d724d1163e48d343f3e5591d83))
* **types:** map SQL Server DATETIMEOFFSET + Oracle BINARY_FLOAT/DOUBLE ([cb73d94](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/cb73d94ce2e6b9e8fd612caa55b4fe202e69c392))

### Features

* **bench:** add end-to-end perf benchmark suite under misc/bench ([aa5dc74](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/aa5dc74f9233f87c24a3777484ca11bc22d8d21b))
* **bench:** add HikariCP observability panels to Grafana dashboard ([132ef4b](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/132ef4bb2b15121688e9952942f56a6091f31703))
* **jdbc:** per-driver engine defaults applied at datasource load ([14b4336](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/14b43369a2c44f4c0d978a0e3b5e7760970043d9))

### Performance Improvements

* **streaming:** hoist nullability + adaptive ByteBuffer size hint ([04d56c9](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/04d56c95b9995d67d03d1e5b657a9256b87df8c0)), closes [#1](https://github.com/arkhn/clickhouse-jdbc-bridge/issues/1)
* **streaming:** raise default batch_size to 4096 and fetch_size to 16384 ([a2fa4ae](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/a2fa4ae8882868eaf2c3bc5fc37ad40477dc6505))

## [1.0.4](https://github.com/arkhn/clickhouse-jdbc-bridge/compare/v1.0.3...v1.0.4) (2026-05-13)

### Bug Fixes

* improve timeout and relax tls security defaults ([bb42622](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/bb426226d1e8381542761037851364bcc41c5abe))

## [1.0.3](https://github.com/arkhn/clickhouse-jdbc-bridge/compare/v1.0.2...v1.0.3) (2026-05-13)

### Bug Fixes

* release process don't push latest ([77fac1d](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/77fac1d7d3a1a0311756055a715ad22440967b19))

## [1.0.2](https://github.com/arkhn/clickhouse-jdbc-bridge/compare/v1.0.1...v1.0.2) (2026-05-12)

### Bug Fixes

* shaded expectations ([c6aa4e8](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/c6aa4e838c4575294999f3ad02a1e04e57d8430d))

## [1.0.1](https://github.com/arkhn/clickhouse-jdbc-bridge/compare/v1.0.0...v1.0.1) (2026-05-12)

### Bug Fixes

* remove maven artifact for now ([d11f69a](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/d11f69a5649e676b9145902b1b0dcb7ad72fa3d7))

## 1.0.0 (2026-05-12)

### Bug Fixes

* **ci:** packaging and shading failed fixed it with micrometer downgrade ([aa3ddba](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/aa3ddbacfe9ce83c7d306d6d5ad490462cf650a3))
* corrupting concurrency in recent mvn versions ([27587b3](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/27587b348ce93c6f75c42ac44e4764527aa0ea9e))
* corrupting concurrency in recent mvn versions ([bccf60e](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/bccf60e6161bbb5303a38e819a201744078fe9de))
* **http:** HTTP 416 range failed ([1d8ee58](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/1d8ee58e3ce81daec892640999f9f8b1fce75fe5))
* improve ClickHouse readiness checks in integration test ([38678b6](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/38678b6ae48aa7edcd4dd8741fc9f903de0b0207))
* logs handling ([5494fd7](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/5494fd78cd7d330a1ab4c9d394afa1e4518cdde2))
* missing http deps ([aad421d](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/aad421df73475f8080817d2b8d13f5154784b7c2))
* release process and builds ([44e9a3a](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/44e9a3a14f4dc53ed34d2981cd8af59c811a1e64))
* releaser ([24147a2](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/24147a2791dd4c1e99db47e68af793c8ecc439e0))
* remaining logging and compat issues + release ([e31ed4a](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/e31ed4ac0d4483279bafaa5528716f789547e032))

### Features

* add base/full Docker image variants and update workflows ([1349e40](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/1349e40a1888b853900d1ffd4bce44d2d42d2528))
* add e2e test from clickhouse to mysql ([ecb57ad](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/ecb57ada57ae3731e4d21749de03c51958b323ec))
* add moar loads ([b9ef77a](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/b9ef77a40f50cd324acf926f290a917debf73b6f))
* add publication of the project to Arkhn's dockerhub ([c90a4e3](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/c90a4e318dbc9c2a730a1e2da301a8237b15cec9))
* upgrade dependency to be ready for java 25 ([9f57004](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/9f570047e481ceace1725ef15776f4160f604a17))
* upgrade java version in dockerfile as well and deps ([230ede5](https://github.com/arkhn/clickhouse-jdbc-bridge/commit/230ede5b7d386d8dff0171b567e9c83fda661544))

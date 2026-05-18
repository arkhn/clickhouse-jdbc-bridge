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

-- ClickBench-flavoured 'hits' schema for SQL Server + a wide_types table for type-mapping stress.
-- Idempotent: bails if tables already have the target row count.
--
-- Usage:
--   sqlcmd -S sqlserver -U sa -P "$MSSQL_SA_PASSWORD" -No -d master -v ROWS=1000000 -i init-mssql.sql

:setvar ROWS 1000000

IF DB_ID('bench') IS NULL
BEGIN
    CREATE DATABASE bench;
END
GO

USE bench;
GO

IF OBJECT_ID('dbo.hits', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.hits (
        watchid          BIGINT       NOT NULL PRIMARY KEY,
        userid           BIGINT       NOT NULL,
        eventtime        DATETIME2    NOT NULL,
        eventdate        DATE         NOT NULL,
        counterid        INT          NOT NULL,
        title            NVARCHAR(512) NOT NULL,
        url              NVARCHAR(1024) NOT NULL,
        referer          NVARCHAR(1024) NOT NULL,
        is_robot         BIT          NOT NULL,
        os               TINYINT      NOT NULL,
        useragent        TINYINT      NOT NULL,
        resolution_width INT          NOT NULL,
        resolution_height INT         NOT NULL,
        flash_minor      TINYINT      NOT NULL,
        revenue          DECIMAL(18,4) NOT NULL
    );
    CREATE INDEX ix_hits_userid ON dbo.hits(userid);
    CREATE INDEX ix_hits_eventdate ON dbo.hits(eventdate);
END
GO

IF OBJECT_ID('dbo.wide_types', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.wide_types (
        id               BIGINT       NOT NULL PRIMARY KEY,
        c_tinyint        TINYINT      NULL,
        c_smallint       SMALLINT     NULL,
        c_int            INT          NULL,
        c_bigint         BIGINT       NULL,
        c_decimal        DECIMAL(18,6) NULL,
        c_float          FLOAT        NULL,
        c_real           REAL         NULL,
        c_money          MONEY        NULL,
        c_bit            BIT          NULL,
        c_char           CHAR(16)     NULL,
        c_varchar        VARCHAR(256) NULL,
        c_nvarchar       NVARCHAR(256) NULL,
        c_text           VARCHAR(MAX) NULL,
        c_date           DATE         NULL,
        c_time           TIME(3)      NULL,
        c_datetime       DATETIME     NULL,
        c_datetime2      DATETIME2(6) NULL,
        c_datetimeoffset DATETIMEOFFSET NULL,
        c_binary         VARBINARY(64) NULL,
        c_uniqueidentifier UNIQUEIDENTIFIER NULL
    );
END
GO

-- Populate hits if it's empty or under the target.
DECLARE @existing BIGINT = (SELECT COUNT_BIG(*) FROM dbo.hits);
IF @existing < $(ROWS)
BEGIN
    PRINT CONCAT('Generating ', $(ROWS) - @existing, ' rows into dbo.hits (current=', @existing, ')...');

    ;WITH
      n0 AS (SELECT v FROM (VALUES (0),(0),(0),(0),(0),(0),(0),(0),(0),(0)) AS x(v)),  -- 10
      n1 AS (SELECT a.v FROM n0 a CROSS JOIN n0 b),                                     -- 100
      n2 AS (SELECT a.v FROM n1 a CROSS JOIN n1 b),                                     -- 10,000
      n3 AS (SELECT a.v FROM n2 a CROSS JOIN n1 b),                                     -- 1,000,000
      seeded AS (SELECT TOP ($(ROWS) - @existing)
                        ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) + @existing AS rn
                 FROM n3)
    INSERT INTO dbo.hits (watchid, userid, eventtime, eventdate, counterid, title, url, referer,
                          is_robot, os, useragent, resolution_width, resolution_height, flash_minor, revenue)
    SELECT
        rn                                                    AS watchid,
        ABS(CHECKSUM(NEWID())) % 1000000                      AS userid,
        DATEADD(SECOND, ABS(CHECKSUM(NEWID())) % 31536000, '2024-01-01') AS eventtime,
        DATEADD(DAY,    ABS(CHECKSUM(NEWID())) % 365,        '2024-01-01') AS eventdate,
        ABS(CHECKSUM(NEWID())) % 10000                        AS counterid,
        CONCAT('Title ', rn)                                  AS title,
        CONCAT('https://example.com/page/', rn)               AS url,
        CONCAT('https://referer.example/', (rn % 1000))       AS referer,
        CAST(ABS(CHECKSUM(NEWID())) % 2 AS BIT)               AS is_robot,
        ABS(CHECKSUM(NEWID())) % 256                          AS os,
        ABS(CHECKSUM(NEWID())) % 256                          AS useragent,
        (ABS(CHECKSUM(NEWID())) % 8) * 320                    AS resolution_width,
        (ABS(CHECKSUM(NEWID())) % 6) * 240                    AS resolution_height,
        ABS(CHECKSUM(NEWID())) % 20                           AS flash_minor,
        CAST((ABS(CHECKSUM(NEWID())) % 100000) / 100.0 AS DECIMAL(18,4)) AS revenue
    FROM seeded
    OPTION (MAXDOP 4);

    PRINT 'hits load done.';
END
ELSE
BEGIN
    PRINT CONCAT('Skipping hits load — already has ', @existing, ' rows.');
END
GO

-- wide_types: 10k rows is plenty for type-conversion benchmarks.
IF (SELECT COUNT_BIG(*) FROM dbo.wide_types) < 10000
BEGIN
    PRINT 'Generating 10000 rows into dbo.wide_types...';
    ;WITH
      n0 AS (SELECT v FROM (VALUES (0),(0),(0),(0),(0),(0),(0),(0),(0),(0)) AS x(v)),
      n1 AS (SELECT a.v FROM n0 a CROSS JOIN n0 b),
      n2 AS (SELECT a.v FROM n1 a CROSS JOIN n1 b),
      seeded AS (SELECT TOP 10000 ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS rn FROM n2)
    INSERT INTO dbo.wide_types
        (id, c_tinyint, c_smallint, c_int, c_bigint, c_decimal, c_float, c_real, c_money, c_bit,
         c_char, c_varchar, c_nvarchar, c_text, c_date, c_time, c_datetime, c_datetime2, c_datetimeoffset,
         c_binary, c_uniqueidentifier)
    SELECT
        rn,
        rn % 256, rn % 32767, rn, rn * 1000000,
        CAST(rn / 7.0 AS DECIMAL(18,6)), rn * 1.5, CAST(rn * 1.25 AS REAL),
        CAST(rn AS MONEY), CAST(rn % 2 AS BIT),
        CONCAT('ch_', rn),
        CONCAT('vc_', rn),
        CONCAT(N'nvc_', rn, N' éàü'),
        REPLICATE('x', (rn % 200) + 1),
        DATEADD(DAY, rn % 3650, '2000-01-01'),
        CAST(DATEADD(SECOND, rn % 86400, '00:00:00') AS TIME),
        DATEADD(MINUTE, rn, '2024-01-01'),
        DATEADD(MICROSECOND, rn % 1000000, CAST('2024-01-01' AS DATETIME2(6))),
        SWITCHOFFSET(CAST(DATEADD(MINUTE, rn, '2024-01-01') AS DATETIMEOFFSET), '+02:00'),
        CAST(CONCAT('0x', RIGHT(CONCAT('00000000', CONVERT(VARCHAR(16), rn, 2)), 8)) AS VARBINARY(64)),
        NEWID()
    FROM seeded;
END
GO

PRINT '== mssql datagen complete ==';
SELECT COUNT_BIG(*) AS hits_rows FROM dbo.hits;
SELECT COUNT_BIG(*) AS wide_rows FROM dbo.wide_types;
GO

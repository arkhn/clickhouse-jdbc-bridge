-- ClickBench-flavoured 'hits' schema for Oracle + wide_types for type-mapping stress.
-- Idempotent. Run as the bench user.
--
-- Usage:
--   sqlplus bench/bench_password@oracle:1521/FREEPDB1 @init-oracle.sql 1000000

DEFINE TARGET_ROWS = &1;

SET SERVEROUTPUT ON SIZE UNLIMITED
SET ECHO OFF
SET FEEDBACK OFF
SET TIMING OFF

-- hits table
DECLARE
  v_exists NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_exists FROM user_tables WHERE table_name = 'HITS';
  IF v_exists = 0 THEN
    EXECUTE IMMEDIATE q'[
      CREATE TABLE hits (
        watchid           NUMBER(19)    NOT NULL PRIMARY KEY,
        userid            NUMBER(19)    NOT NULL,
        eventtime         TIMESTAMP(6)  NOT NULL,
        eventdate         DATE          NOT NULL,
        counterid         NUMBER(10)    NOT NULL,
        title             VARCHAR2(512) NOT NULL,
        url               VARCHAR2(1024) NOT NULL,
        referer           VARCHAR2(1024) NOT NULL,
        is_robot          NUMBER(1)     NOT NULL,
        os                NUMBER(3)     NOT NULL,
        useragent         NUMBER(3)     NOT NULL,
        resolution_width  NUMBER(10)    NOT NULL,
        resolution_height NUMBER(10)    NOT NULL,
        flash_minor       NUMBER(3)     NOT NULL,
        revenue           NUMBER(18,4)  NOT NULL
      )
    ]';
    EXECUTE IMMEDIATE 'CREATE INDEX ix_hits_userid    ON hits(userid)';
    EXECUTE IMMEDIATE 'CREATE INDEX ix_hits_eventdate ON hits(eventdate)';
  END IF;
END;
/

-- wide_types table
DECLARE
  v_exists NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_exists FROM user_tables WHERE table_name = 'WIDE_TYPES';
  IF v_exists = 0 THEN
    EXECUTE IMMEDIATE q'[
      CREATE TABLE wide_types (
        id          NUMBER(19) NOT NULL PRIMARY KEY,
        c_number    NUMBER(10),
        c_bignum    NUMBER(19),
        c_decimal   NUMBER(18,6),
        c_float     BINARY_FLOAT,
        c_double    BINARY_DOUBLE,
        c_char      CHAR(16),
        c_varchar2  VARCHAR2(256),
        c_nvarchar2 NVARCHAR2(256),
        c_clob      CLOB,
        c_date      DATE,
        c_ts        TIMESTAMP(6),
        c_ts_tz     TIMESTAMP(6) WITH TIME ZONE,
        c_ts_ltz    TIMESTAMP(6) WITH LOCAL TIME ZONE,
        c_raw       RAW(64),
        c_blob      BLOB,
        c_intvy     INTERVAL YEAR(3) TO MONTH,
        c_intvd     INTERVAL DAY(3) TO SECOND(3)
      )
    ]';
  END IF;
END;
/

-- Populate hits in 100k-row batches to limit redo log pressure.
DECLARE
  v_existing NUMBER;
  v_target   NUMBER := &TARGET_ROWS;
  v_batch    NUMBER := 100000;
  v_done     NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_existing FROM hits;
  IF v_existing >= v_target THEN
    DBMS_OUTPUT.PUT_LINE('Skipping hits load - already has ' || v_existing || ' rows.');
    RETURN;
  END IF;

  v_done := v_existing;
  DBMS_OUTPUT.PUT_LINE('Generating ' || (v_target - v_existing) || ' rows into hits...');

  WHILE v_done < v_target LOOP
    INSERT /*+ APPEND */ INTO hits
      (watchid, userid, eventtime, eventdate, counterid, title, url, referer,
       is_robot, os, useragent, resolution_width, resolution_height, flash_minor, revenue)
    SELECT
        v_done + LEVEL                                                          AS watchid,
        MOD(DBMS_RANDOM.VALUE(0, 1000000) * 1, 1000000)                         AS userid,
        TIMESTAMP '2024-01-01 00:00:00' + NUMTODSINTERVAL(DBMS_RANDOM.VALUE(0,31536000), 'SECOND') AS eventtime,
        DATE '2024-01-01' + TRUNC(DBMS_RANDOM.VALUE(0,365))                     AS eventdate,
        TRUNC(DBMS_RANDOM.VALUE(0,10000))                                       AS counterid,
        'Title ' || (v_done + LEVEL)                                            AS title,
        'https://example.com/page/' || (v_done + LEVEL)                         AS url,
        'https://referer.example/' || MOD(v_done + LEVEL, 1000)                 AS referer,
        TRUNC(DBMS_RANDOM.VALUE(0,2))                                           AS is_robot,
        TRUNC(DBMS_RANDOM.VALUE(0,256))                                         AS os,
        TRUNC(DBMS_RANDOM.VALUE(0,256))                                         AS useragent,
        TRUNC(DBMS_RANDOM.VALUE(0,8)) * 320                                     AS resolution_width,
        TRUNC(DBMS_RANDOM.VALUE(0,6)) * 240                                     AS resolution_height,
        TRUNC(DBMS_RANDOM.VALUE(0,20))                                          AS flash_minor,
        ROUND(DBMS_RANDOM.VALUE(0,1000), 4)                                     AS revenue
    FROM dual CONNECT BY LEVEL <= LEAST(v_batch, v_target - v_done);
    COMMIT;
    v_done := v_done + LEAST(v_batch, v_target - v_done);
    DBMS_OUTPUT.PUT_LINE('  loaded ' || v_done || ' / ' || v_target);
  END LOOP;
END;
/

-- wide_types: 10k rows
DECLARE
  v_existing NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_existing FROM wide_types;
  IF v_existing >= 10000 THEN
    DBMS_OUTPUT.PUT_LINE('Skipping wide_types load - already has ' || v_existing || ' rows.');
    RETURN;
  END IF;
  DBMS_OUTPUT.PUT_LINE('Generating 10000 rows into wide_types...');
  INSERT INTO wide_types
    (id, c_number, c_bignum, c_decimal, c_float, c_double,
     c_char, c_varchar2, c_nvarchar2, c_clob,
     c_date, c_ts, c_ts_tz, c_ts_ltz,
     c_raw, c_blob, c_intvy, c_intvd)
  SELECT
      LEVEL,
      MOD(LEVEL, 32767),
      LEVEL * 1000000,
      LEVEL / 7,
      LEVEL * 1.5,
      LEVEL * 1.25,
      'ch_' || LEVEL,
      'vc_' || LEVEL,
      'nvc_' || LEVEL,
      RPAD('x', MOD(LEVEL, 200) + 1, 'x'),
      DATE '2000-01-01' + MOD(LEVEL, 3650),
      TIMESTAMP '2024-01-01 00:00:00' + NUMTODSINTERVAL(LEVEL, 'MINUTE'),
      FROM_TZ(TIMESTAMP '2024-01-01 00:00:00' + NUMTODSINTERVAL(LEVEL, 'MINUTE'), '+02:00'),
      TIMESTAMP '2024-01-01 00:00:00' + NUMTODSINTERVAL(LEVEL, 'MINUTE'),
      UTL_RAW.CAST_TO_RAW('r_' || LEVEL),
      UTL_RAW.CAST_TO_RAW(RPAD('b', MOD(LEVEL, 64) + 1, 'b')),
      NUMTOYMINTERVAL(MOD(LEVEL, 12) + 1, 'MONTH'),
      NUMTODSINTERVAL(MOD(LEVEL, 86400), 'SECOND')
  FROM dual CONNECT BY LEVEL <= 10000;
  COMMIT;
END;
/

PROMPT == oracle datagen complete ==
SELECT COUNT(*) AS hits_rows FROM hits;
SELECT COUNT(*) AS wide_rows FROM wide_types;

EXIT;

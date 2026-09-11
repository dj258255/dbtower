-- 인스턴스별 앱 스키마(선택). 모니터 계정과 앱 데이터의 소유 스키마가 다른 기종(현재 Oracle)에서 딕셔너리 조회와 콘솔 세션이 볼 스키마다.
-- null이면 전역 설정(dbtower.oracle.app-schema)을 따른다. 전역 하나로는 인스턴스마다 앱 스키마가 다른 구성을 담지 못했다(VERIFICATION 133절).
ALTER TABLE database_instance ADD COLUMN app_schema VARCHAR(128);

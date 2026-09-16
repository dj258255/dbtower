"""DBTower AI 운영 작업의 실행면(게이트웨이, 릴레이, 실행기).

DBTower는 작업 상태, 권한, 사실, 결과 검증의 기준 시스템이다. 이 패키지는 그 바깥에서
Slack 입구, Outbox -> Redis Streams 릴레이, LangGraph 실행기, 참고 자료 검색, 알림을 맡는다.
대상 DB에 직접 접속하지 않고, 모델 호출도 DBTower의 AiAnalyzer 한 곳을 거친다.
"""

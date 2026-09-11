package io.dbtower.operator.model;

/**
 * 콘솔 조회 결과의 열. name은 결과에 보이는 이름(별칭), baseName은 드라이버가 알려주는 원래 컬럼명이다.
 *
 * <p>마스킹이 둘 다 본다: {@code SELECT email AS e}처럼 별칭으로 이름 기반 규칙을 피하는 경로를 줄이기 위해서다.
 * 드라이버에 따라 baseName도 별칭으로 채워지므로 완전한 방어는 아니다. 본 방어선은 콘솔 계정의 컬럼 단위 권한이다.
 */
public record ResultColumn(String name, String baseName, String typeName) {
}

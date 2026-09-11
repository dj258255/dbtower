-- 플랫폼 역할을 쓰는 사람 기준으로 나눈다(VERIFICATION 135절): 관제만 보는 사람(VIEWER), 요청자(REQUESTER), 승인자(APPROVER),
-- 운영자(OPERATOR), 플랫폼 관리자(ADMIN). 포함 관계는 코드(PlatformRoles)가 정한다.
ALTER TABLE platform_user DROP CONSTRAINT platform_user_role_check;
ALTER TABLE platform_user ADD CONSTRAINT platform_user_role_check
    CHECK (role IN ('VIEWER', 'REQUESTER', 'APPROVER', 'OPERATOR', 'ADMIN'));

-- 기존 VIEWER는 워크벤치 조회와 변경 요청을 써 왔다. VIEWER가 "관제만 보는 사람"으로 좁혀지므로, 이미 쓰던 권한을 잃지 않게 REQUESTER로 옮긴다.
UPDATE platform_user SET role = 'REQUESTER' WHERE role = 'VIEWER';

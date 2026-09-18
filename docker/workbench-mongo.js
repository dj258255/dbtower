// 워크벤치 콘솔 계정 + 데모 데이터 — MongoDB (근거: docs/operate/least-privilege.md "워크벤치 콘솔 계정")
// 수동 실행: docker exec -i dbtower-mongo mongosh -u root -p dbtower1234 --authenticationDatabase admin --quiet < docker/workbench-mongo.js
// 여러 번 실행해도 결과가 같다. 플랫폼은 authSource=admin을 가정하므로 계정은 admin db에 만든다.
db = db.getSiblingDB('sample');
if (db.customers.countDocuments() === 0) {
  db.customers.insertMany([
    { _id: 1, name: '홍길동', email: 'hong@example.com', phone: '01012345678', grade: 'VIP' },
    { _id: 2, name: '김철수', email: 'kim@example.com', phone: '01098765432', grade: 'GOLD' },
    { _id: 3, name: '이영희', email: 'lee@example.com', phone: '01055554444', grade: 'SILVER' },
  ]);
}

const admin = db.getSiblingDB('admin');
// 조회 계정: read@sample만 — find·aggregate·count·distinct
if (!admin.getUser('dbtower_reader')) {
  admin.createUser({ user: 'dbtower_reader', pwd: 'dbtower1234', roles: [{ role: 'read', db: 'sample' }] });
}
// 변경 계정(3단계, 승인된 티켓만 실행)
if (!admin.getUser('dbtower_writer')) {
  admin.createUser({ user: 'dbtower_writer', pwd: 'dbtower1234', roles: [{ role: 'readWrite', db: 'sample' }] });
}
print('workbench accounts ready');

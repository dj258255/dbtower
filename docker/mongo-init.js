// 최초 기동 시 1회 실행 — 샘플 데이터 시드 (인덱스는 _id만: COLLSCAN 시나리오 재현용)
db = db.getSiblingDB('sample');
const docs = [];
for (let i = 1; i <= 20000; i++) {
  docs.push({ name: 'user' + i, category: i % 10, price: i % 1000 });
}
db.users.insertMany(docs);

// 최소 권한 모니터링 계정 (실측 근거: docs/least-privilege.md)
// read@sample: 대상 db 컬렉션 읽기(table-stats의 listCollections/$collStats, explain의 find)
// clusterMonitor@admin: replSetGetStatus + 모든 db의 system.profile find
//   (read 롤은 system.profile을 못 읽는다 — clusterMonitor가 { db: "", collection: "system.profile" } find를 보유)
// 플랫폼은 authSource=admin을 가정하므로 admin db에 만든다.
db.getSiblingDB('admin').createUser({
  user: 'dbtower_monitor',
  pwd: 'dbtower1234',
  roles: [
    { role: 'read', db: 'sample' },
    { role: 'clusterMonitor', db: 'admin' },
  ],
});

// 최초 기동 시 1회 실행 — 샘플 데이터 시드 (인덱스는 _id만: COLLSCAN 시나리오 재현용)
db = db.getSiblingDB('sample');
const docs = [];
for (let i = 1; i <= 20000; i++) {
  docs.push({ name: 'user' + i, category: i % 10, price: i % 1000 });
}
db.users.insertMany(docs);

db = db.getSiblingDB("mongoDBLab3");

db.createUser({
  user: "lab3_user",
  pwd: "lab3_password",
  roles: [
    { role: "readWrite", db: "mongoDBLab3" }
  ]
});
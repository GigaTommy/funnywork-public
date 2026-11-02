CREATE TABLE recovery_generation (
 id INTEGER PRIMARY KEY CHECK (id=1),
 generation VARCHAR(64) NOT NULL
);
INSERT INTO recovery_generation(id,generation) VALUES(1,'initial');

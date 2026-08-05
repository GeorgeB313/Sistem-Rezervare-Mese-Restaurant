-- DDL pentru mese si rezervari
DROP TABLE IF EXISTS rezervari;
DROP TABLE IF EXISTS mese;
CREATE TABLE IF NOT EXISTS mese (
  id INTEGER PRIMARY KEY,
  nume TEXT,
  capacitate INTEGER,
  zona TEXT,
  pozitie_x INTEGER,
  pozitie_y INTEGER,
  langa_fereastra INTEGER
);
CREATE TABLE IF NOT EXISTS rezervari (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  masa_id INTEGER,
  nume_client TEXT,
  nr_persoane INTEGER,
  data_ora TEXT,
  preferinta_fereastra INTEGER,
  status TEXT,
  FOREIGN KEY(masa_id) REFERENCES mese(id)
);

-- seed mese
INSERT INTO mese (id, nume, capacitate, zona, pozitie_x, pozitie_y, langa_fereastra) VALUES
(1, 'M1', 2, 'fereastra', 1, 1, 1),
(2, 'M2', 2, 'fereastra', 2, 1, 1),
(3, 'M3', 4, 'fereastra', 3, 1, 1),
(4, 'M4', 4, 'fereastra', 4, 1, 1),
(5, 'M5', 6, 'fereastra', 5, 1, 1),
(6, 'M6', 2, 'central', 1, 2, 0),
(7, 'M7', 2, 'central', 2, 2, 0),
(8, 'M8', 4, 'central', 3, 2, 0),
(9, 'M9', 4, 'central', 4, 2, 0),
(10, 'M10', 6, 'central', 5, 2, 0),
(11, 'M11', 2, 'central', 1, 3, 0),
(12, 'M12', 2, 'central', 2, 3, 0),
(13, 'M13', 4, 'central', 3, 3, 0),
(14, 'M14', 4, 'central', 4, 3, 0),
(15, 'M15', 6, 'central', 5, 3, 0),
(16, 'M16', 2, 'intrare', 1, 4, 0),
(17, 'M17', 2, 'intrare', 2, 4, 0),
(18, 'M18', 4, 'intrare', 3, 4, 0),
(19, 'M19', 4, 'intrare', 4, 4, 0),
(20, 'M20', 6, 'intrare', 5, 4, 0);

-- seed rezervari de exemplu
INSERT INTO rezervari (masa_id, nume_client, nr_persoane, data_ora, preferinta_fereastra, status) VALUES
(3, 'Popescu Ana', 3, '2025-12-11 19:00', 1, 'confirmata'),
(8, 'Ionescu Mihai', 4, '2025-12-11 20:00', 0, 'confirmata');

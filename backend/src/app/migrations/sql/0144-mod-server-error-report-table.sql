ALTER TABLE server_error_report DROP CONSTRAINT server_error_report_pkey;

DELETE FROM server_error_report a
USING server_error_report b
WHERE a.id = b.id
  AND a.ctid < b.ctid;

ALTER TABLE server_error_report ADD PRIMARY KEY (id);

CREATE INDEX server_error_report__version__idx
    ON server_error_report ( version );

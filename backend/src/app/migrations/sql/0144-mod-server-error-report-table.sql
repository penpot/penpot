ALTER TABLE server_error_report DROP CONSTRAINT server_error_report_pkey;
ALTER TABLE server_error_report ADD PRIMARY KEY (id);
CREATE INDEX server_error_report__version__idx
    ON server_error_report ( version );

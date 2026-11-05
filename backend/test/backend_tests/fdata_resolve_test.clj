;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.fdata-resolve-test
  "Resolution of file data by backend (`app.features.fdata/resolve-file-data`).

  A row without a `file_data` twin carries no backend: the read queries mark
  it with `coalesce(fd.backend, 'legacy-db')` and hand its blob over as
  `:legacy-data`. The `legacy-db` backend is still supported, so that case
  has to resolve to the blob instead of nil."
  (:require
   [app.features.fdata :as fdata]
   [clojure.test :as t]))

(t/deftest legacy-db-resolves-the-legacy-blob
  (let [blob (vec (.getBytes "legacy-blob" "UTF-8"))
        file {:backend "legacy-db"
              :legacy-data (byte-array blob)}
        out  (fdata/resolve-file-data nil file)]
    (t/is (= blob (vec (:data out))))
    (t/is (not (contains? out :legacy-data)))))

(t/deftest missing-backend-defaults-to-legacy-db
  ;; The queries leave `:backend` nil when the `LEFT JOIN` with `file_data`
  ;; finds no twin; the dispatch default keeps that row on `legacy-db`.
  (let [blob (vec (.getBytes "legacy-blob" "UTF-8"))
        file {:legacy-data (byte-array blob)}
        out  (fdata/resolve-file-data nil file)]
    (t/is (= blob (vec (:data out))))
    (t/is (not (contains? out :legacy-data)))))

(t/deftest db-backend-drops-the-legacy-key
  (let [data (vec (.getBytes "db-blob" "UTF-8"))
        file {:backend "db"
              :data (byte-array data)
              :legacy-data (byte-array (vec (.getBytes "stale" "UTF-8")))}
        out  (fdata/resolve-file-data nil file)]
    (t/is (= data (vec (:data out))))
    (t/is (not (contains? out :legacy-data)))))

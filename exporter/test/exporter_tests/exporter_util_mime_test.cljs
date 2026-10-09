;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.exporter-util-mime-test
  "The mimetype and file extension of every artifact type the worker
  settles: the suffix of the temp file and the mtype of the upload."
  (:require
   [cljs.test :as t :include-macros true]
   [exporter.util.mime :as mime]))

(t/deftest get-extension-names-every-artifact-type
  (t/is (= ".png" (mime/get-extension :png)))
  (t/is (= ".jpg" (mime/get-extension :jpeg)))
  (t/is (= ".webp" (mime/get-extension :webp)))
  (t/is (= ".svg" (mime/get-extension :svg)))
  (t/is (= ".pdf" (mime/get-extension :pdf)))
  (t/is (= ".zip" (mime/get-extension :zip))))

(t/deftest get-names-every-artifact-mimetype
  (t/is (= "image/png" (mime/get :png)))
  (t/is (= "image/jpeg" (mime/get :jpeg)))
  (t/is (= "image/webp" (mime/get :webp)))
  (t/is (= "image/svg+xml" (mime/get :svg)))
  (t/is (= "application/pdf" (mime/get :pdf)))
  (t/is (= "application/zip" (mime/get :zip))))

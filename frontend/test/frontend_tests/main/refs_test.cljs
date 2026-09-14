;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.main.refs-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.refs :as refs]
   [cljs.test :as t :include-macros true]))

(def ^:private resolved-uri? @#'refs/resolved-uri?)

(t/deftest resolved-uri-detects-blob-and-data-uris
  (t/is (true? (resolved-uri? "blob:http://localhost/thumb")))
  (t/is (true? (resolved-uri? "data:image/png;base64,iVBOR"))))

(t/deftest resolved-uri-rejects-plain-media-ids
  (t/is (false? (resolved-uri? "275c9c60-7f5e-4a2b-9f1c-2d3e4f5a6b7c")))
  (t/is (false? (resolved-uri? ""))))

(t/deftest resolved-uri-tolerates-non-string-uris
  ;; Thumbnail :uri values arrive from the server over transit, so
  ;; media-ids decode to UUID objects instead of strings. These must
  ;; fall through to resolve-media instead of raising a TypeError
  ;; (str.lastIndexOf is not a function) that crashes the workspace.
  (t/is (false? (resolved-uri? (uuid/next))))
  (t/is (false? (resolved-uri? nil)))
  (t/is (false? (resolved-uri? 42))))

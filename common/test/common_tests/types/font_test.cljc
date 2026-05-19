;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.font-test
  (:require
   [app.common.schema :as sm]
   [app.common.types.font :as ctf]
   [clojure.test :as t]))

(t/deftest font-family-schema-valid
  (t/is (sm/validate ctf/schema:font-family "Source Sans Pro"))
  (t/is (sm/validate ctf/schema:font-family "Roboto"))
  (t/is (sm/validate ctf/schema:font-family "Open Sans 300"))
  (t/is (sm/validate ctf/schema:font-family "Font-Name_v2"))
  (t/is (sm/validate ctf/schema:font-family "Noto Sans CJK SC"))
  (t/is (sm/validate ctf/schema:font-family "A"))
  ;; hyphens, underscores and dots are allowed
  (t/is (sm/validate ctf/schema:font-family "Fira-Code"))
  (t/is (sm/validate ctf/schema:font-family "font_name"))
  (t/is (sm/validate ctf/schema:font-family "Soucre Sans Pro 3.0"))
  ;; Unicode letters are allowed
  (t/is (sm/validate ctf/schema:font-family "思源黑体"))
  (t/is (sm/validate ctf/schema:font-family "العربية")))

(t/deftest font-family-schema-invalid
  ;; HTML injection characters
  (t/is (not (sm/validate ctf/schema:font-family "evil<script>")))
  (t/is (not (sm/validate ctf/schema:font-family "<test>name")))
  ;; CSS injection characters
  (t/is (not (sm/validate ctf/schema:font-family "evil'name")))
  (t/is (not (sm/validate ctf/schema:font-family "evil\"name")))
  (t/is (not (sm/validate ctf/schema:font-family "evil}name")))
  (t/is (not (sm/validate ctf/schema:font-family "evil;name")))
  (t/is (not (sm/validate ctf/schema:font-family "evil\\name")))
  ;; empty string
  (t/is (not (sm/validate ctf/schema:font-family "")))
  ;; too long
  (t/is (not (sm/validate ctf/schema:font-family (apply str (repeat 251 "a"))))))

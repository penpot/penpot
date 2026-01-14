;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.modules
  (:refer-clojure :exclude [import])
  (:require-macros [app.util.modules])
  (:require
   [shadow.esm :refer [dynamic-import]]))

(defn import
  "Dynamic esm module import import"
  [path]
  (dynamic-import (str path)))

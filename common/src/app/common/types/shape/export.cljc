;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.export
  (:require
   [app.common.schema :as sm]))

(def types #{:png :jpeg :webp :svg :pdf})

(def schema:export
  [:map {:title "ShapeExport"}
   [:type [::sm/one-of types]]
   [:scale ::sm/safe-number]
   [:suffix :string]])

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.text.ui
  (:require
   [app.main.features :as features]
   [app.main.store :as st]
   [app.util.dom :as dom]))

(defn v1-closest-text-editor-content
  [target]
  (.closest ^js target ".public-DraftEditor-content"))

(defn v2-closest-text-editor-content
  [target]
  (.closest ^js target "[data-itype=\"editor\"]"))

(defn closest-text-editor-content
  [target]
  (if (features/active-feature? @st/state "text-editor/v2")
    (v2-closest-text-editor-content target)
    (v1-closest-text-editor-content target)))

(defn some-text-editor-content?
  [target]
  (some? (closest-text-editor-content target)))

(defn v1-get-text-editor-content
  []
  (dom/get-element-by-class "public-DraftEditor-content"))

(defn v2-get-text-editor-content
  []
  (dom/query "[data-itype=\"editor\"]"))

(defn get-text-editor-content
  []
  (if (features/active-feature? @st/state "text-editor/v2")
    (v2-get-text-editor-content)
    (v1-get-text-editor-content)))

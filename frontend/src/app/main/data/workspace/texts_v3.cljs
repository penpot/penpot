;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.texts-v3
  (:require
   [app.common.types.text :as txt]
   [potok.v2.core :as ptk]))

(defn v3-update-text-editor-styles
  [id new-styles]
  (ptk/reify ::v3-update-text-editor-styles
    ptk/UpdateEvent
    (update [_ state]
      (let [merged-styles (merge (txt/get-default-text-attrs)
                                 (get-in state [:workspace-global :default-font])
                                 new-styles)]
        (update-in state [:workspace-wasm-editor-styles id] (fnil merge {}) merged-styles)))))

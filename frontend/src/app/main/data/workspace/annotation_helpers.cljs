;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.annotation-helpers
  (:require
   [app.common.data.macros :as dm]
   [app.common.logging :as log]
   [app.common.types.components-list :as ctkl]
   [app.main.refs :as refs]))

;; Change this to :info :debug or :trace to debug this module, or :warn to reset to default
(log/set-level! :warn)


(defn get-main-annotation
  [shape libraries]
  (let [library        (dm/get-in libraries [(:component-file shape) :data])
        component      (ctkl/get-component library (:component-id shape) true)]
    (:annotation component)))

(defn get-main-annotation-viewer
  [shape libraries]
  (let [objects        (deref (refs/get-viewer-objects))
        parent         (get objects (:parent-id shape))]
    (get-main-annotation parent libraries)))

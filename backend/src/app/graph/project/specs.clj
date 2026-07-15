;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.graph.project.specs
  "Malli schemas for graph projection payloads.

  These are intentionally small subsets of the Penpot file model: they
  validate only the attributes written into Ladybug nodes for the
  current vertical slice."
  (:require
   [app.common.schema :as sm]))

(def schema:document
  [:map {:title "GraphDocument"}
   [:id ::sm/uuid]
   [:name :string]
   [:version {:optional true} :int]
   [:revision {:optional true} :int]])

(def schema:page
  [:map {:title "GraphPage"}
   [:id ::sm/uuid]
   [:name :string]
   [:index {:optional true} :int]])

(def schema:shape-node
  [:map {:title "GraphShapeNode"}
   [:id ::sm/uuid]
   [:name :string]])

(def check-document
  (sm/check-fn schema:document
               :type :validation
               :code :graph-document-projection
               :hint "invalid graph document projection"))

(def check-page
  (sm/check-fn schema:page
               :type :validation
               :code :graph-page-projection
               :hint "invalid graph page projection"))

(def check-shape-node
  (sm/check-fn schema:shape-node
               :type :validation
               :code :graph-shape-projection
               :hint "invalid graph shape projection"))

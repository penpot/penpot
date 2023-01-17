;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.queries.viewer
  (:require
   [app.common.spec :as us]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.viewer :as viewer]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

(s/def ::components-v2 ::us/boolean)
(s/def ::view-only-bundle
  (s/and ::viewer/get-view-only-bundle
         (s/keys :opt-un [::components-v2])))

(sv/defmethod ::view-only-bundle
  {::rpc/auth false
   ::doc/added "1.3"
   ::doc/deprecated "1.18"}
  [{:keys [pool] :as cfg} {:keys [features components-v2] :as params}]
  (with-open [conn (db/open pool)]
    (let [;; BACKWARD COMPATIBILTY with the components-v2 parameter
          features (cond-> (or features #{})
                     components-v2 (conj "components/v2"))
          params   (assoc params :features features)]
      (viewer/get-view-only-bundle conn params))))

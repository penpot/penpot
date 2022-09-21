;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.mutations.verify-token
  (:require
   [app.db :as db]
   [app.tokens :as tokens]
   [app.rpc.doc :as-alias doc]
   [app.rpc.commands.verify-token :refer [process-token]]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

(s/def ::verify-token
  (s/keys :req-un [::token]
          :opt-un [::profile-id]))

(sv/defmethod ::verify-token
  {:auth false
   ::doc/added "1.1"
   ::doc/deprecated "1.15"}
  [{:keys [pool sprops] :as cfg} {:keys [token] :as params}]
  (db/with-atomic [conn pool]
    (let [claims (tokens/verify sprops {:token token})
          cfg    (assoc cfg :conn conn)]
      (process-token cfg params claims))))

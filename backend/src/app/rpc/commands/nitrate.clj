(ns app.rpc.commands.nitrate
  (:require
   [app.common.schema :as sm]
   [app.nitrate :as nitrate]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as-alias doc]
   [app.util.services :as sv]))


(def schema:connectivity
  [:map {:title "nitrate-connectivity"}
   [:licenses ::sm/boolean]])

(sv/defmethod ::get-nitrate-connectivity
  {::rpc/auth false
   ::doc/added "1.18"
   ::sm/params [:map]
   ::sm/result schema:connectivity}
  [cfg _params]
  (nitrate/connectivity cfg))

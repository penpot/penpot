;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.admin.misc
  "Admin miscellaneous commands, served through `/api/admin/methods`.

  Small diagnostic tools that do not deserve their own namespace:
  currently just the virtual clock. Access control lives in
  `wrap-authentication` (the `\"superuser\"` permission), not here."
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as-alias sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as doc]
   [app.setup.clock :as clock]
   [app.util.services :as sv]))

;; ----------------------------------------------------------------
;; Virtual clock
;; ----------------------------------------------------------------

(def schema:get-virtual-clock-result
  [:map
   [:offset-millis {:optional true} ::sm/int]
   [:now ct/schema:inst]
   [:clock ::sm/text]])

(sv/defmethod ::get-virtual-clock
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params [:map]
   ::sm/result schema:get-virtual-clock-result}
  [_ {:keys [::rpc/profile-id]}]
  (let [offset (clock/get-offset profile-id)]
    {:offset-millis (some-> offset (.toMillis))
     :now           (ct/now)
     :clock         (if offset "offset" "system")}))

(def schema:set-virtual-clock-params
  [:map {:title "set-virtual-clock-params"}
   [:offset {:optional true} ct/schema:duration]
   [:reset {:optional true} ::sm/boolean]])

(sv/defmethod ::set-virtual-clock
  {::doc/added "2.20"
   ::rpc/perms #{"superuser"}
   ::sm/params schema:set-virtual-clock-params
   ::sm/result schema:get-virtual-clock-result}
  [_ {:keys [::rpc/profile-id offset reset]}]
  (when (= "production" (cf/get :tenant))
    (ex/raise :type :restriction
              :code :operation-not-allowed
              :hint "virtual clock cannot be changed in production"))
  (when (and (nil? offset) (not reset))
    (ex/raise :type :validation
              :code :missing-arguments
              :hint "either offset or reset is required"))
  ;; Mirror `/dbg`: a reset (or a zero duration) clears the offset.
  ;; The raw millis go out untouched: the response encoder would
  ;; re-format an already formatted string.
  (if (or reset (and (some? offset) (.isZero ^java.time.Duration offset)))
    (clock/assign-offset profile-id nil)
    (clock/assign-offset profile-id offset))
  (let [current (clock/get-offset profile-id)]
    {:offset-millis (some-> current (.toMillis))
     :now           (ct/now)
     :clock         (if current "offset" "system")}))

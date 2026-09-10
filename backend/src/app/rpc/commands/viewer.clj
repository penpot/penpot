;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.rpc.commands.viewer
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.helpers :as cfh]
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.teams :as teams]
   [app.rpc.cond :as-alias cond]
   [app.rpc.doc :as-alias doc]
   [app.rpc.permissions :as perms]
   [app.util.services :as sv]
   [cuerdas.core :as str]))

;; --- QUERY: View Only Bundle

(def ^:private view-only-data-keys
  "Minimal `:data` keys a view-only bundle exposes, for the primary file
   and for each linked library alike. Keep both scopes on this single
   definition so they cannot drift apart."
  [:id :options :pages :pages-index :components])

(defn- remove-not-allowed-pages
  [data allowed]
  (-> data
      (update :pages (fn [pages] (filterv #(contains? allowed %) pages)))
      (update :pages-index select-keys allowed)))

(defn- find-library-refs
  "Return `{lib-id #{component-id}}` referenced by the given shapes."
  [shapes]
  (reduce (fn [acc shape]
            (if (and (map? shape)
                     (some? (:component-file shape))
                     (some? (:component-id shape)))
              (update acc (:component-file shape) (fnil conj #{}) (:component-id shape))
              acc))
          {}
          shapes))

(defn- component-shapes
  "Shapes owned by a library component: the subtree rooted at its main
   instance. Stored components carry no `:objects`; they point at the main
   instance through `:main-instance-id`/`:main-instance-page` instead. Looks
   the page up in the component's own library first and in any other loaded
   library as fallback (cloned or moved components); ties break by library
   id order so the fallback is deterministic. Returns nil when the main
   instance cannot be resolved."
  [libs-by-id lib-id {:keys [main-instance-id main-instance-page]}]
  (when (and (some? main-instance-id) (some? main-instance-page))
    (let [objects (or (get-in libs-by-id [lib-id :data :pages-index main-instance-page :objects])
                      (some (fn [[_ lib]]
                              (get-in lib [:data :pages-index main-instance-page :objects]))
                            (sort-by key libs-by-id)))]
      (when (and (some? objects) (some? (get objects main-instance-id)))
        (cfh/get-children-with-self objects main-instance-id)))))

(defn- collect-used-library-components
  "Walk the objects of the (already page-scoped) primary file data and of
   the referenced library components themselves, and return a map of
   `{lib-id #{component-id}}` with every library component the allowed
   pages need. Follows nested references to a fixpoint so trimming never
   drops a component that is only reached through another component."
  [primary-data libs-by-id]
  (let [root-shapes (concat (mapcat (comp vals :objects) (vals (:pages-index primary-data)))
                            (mapcat (comp vals :objects) (vals (:components primary-data))))]
    (loop [seen {}]
      (let [nested (mapcat (fn [[lib-id comp-ids]]
                             (mapcat #(component-shapes libs-by-id lib-id
                                                        (get-in libs-by-id [lib-id :data :components %]))
                                     comp-ids))
                           seen)
            merged (merge-with into seen (find-library-refs (concat root-shapes nested)))]
        (if (= merged seen)
          seen
          (recur merged))))))

(defn- trim-library-data
  "Reduce a linked library to the minimum a share-link viewer needs: keep
   the narrow `:data` keys, drop the library's own pages (a share link
   never grants pages of a library), and keep only the components
   referenced by the allowed pages of the primary file. Envelope metadata
   outside `:data` (name, project, sync state) is intentionally preserved:
   the viewer client needs it and it carries no design content."
  [used-refs {:keys [id] :as lib}]
  (-> lib
      (update :data select-keys view-only-data-keys)
      (assoc-in [:data :pages] [])
      (assoc-in [:data :pages-index] {})
      (update-in [:data :components] select-keys (get used-refs id #{}))))

(defn obfuscate-email
  "Obfuscate the `email` for share-link members so the viewer only sees a
   partially redacted address. Accepts any string shape (including nil,
   missing `@`, or a domain with no `.`) and falls back to a fully-masked
   result rather than throwing — the function is called while building the
   view-only bundle for anonymous viewers, so an NPE here would abort the
   entire share-link response."
  [email]
  (let [[name domain]
        (str/split (or email "") "@" 2)

        [_ rest]
        (str/split (or domain "") "." 2)

        name
        (if (> (count name) 3)
          (str (subs name 0 1) (apply str (take (dec (count name)) (repeat "*"))))
          "****")]

    (str name "@****" (when rest (str "." rest)))))

(defn anonymize-member
  [member]
  (-> (select-keys member [:id :email :name :fullname :photo-id])
      (update :email obfuscate-email)
      (assoc :can-read true)))

(defn- get-view-only-bundle
  [{:keys [::db/conn] :as cfg} {:keys [profile-id file-id share-id ::perms] :as params}]
  (let [file    (bfc/get-file cfg file-id)

        project (db/get conn :project
                        {:id (:project-id file)}
                        {:columns [:id :name :team-id]})

        team    (-> (db/get conn :team {:id (:team-id project)})
                    (teams/decode-row))

        members    (cond->> (teams/get-team-members conn (:team-id project))
                     (= :share-link (:type perms))
                     (mapv anonymize-member))

        member-ids (into #{} (map :id) members)

        perms   (assoc perms :in-team (contains? member-ids profile-id))

        _       (-> (cfeat/get-team-enabled-features cf/flags team)
                    (cfeat/check-client-features! (:features params))
                    (cfeat/check-file-features! (:features file)))

        file    (cond-> file
                  (= :share-link (:type perms))
                  (update :data remove-not-allowed-pages (:pages perms))

                  :always
                  (update :data select-keys view-only-data-keys))

        libs    (->> (bfc/get-file-libraries conn file-id)
                     (mapv (fn [{:keys [id] :as lib}]
                             (merge lib (bfc/get-file cfg id)))))

        ;; A share link never grants pages of a linked library, so trim
        ;; each library to the components the allowed pages reference.
        ;; File data may hold lazy pointer-mapped pages/components, so
        ;; realize each file with its own loader before walking it.
        ;; Membership bundles keep the full libraries.
        libs    (if (= :share-link (:type perms))
                  (let [file'      (fdata/realize-pointers cfg file)
                        libs'      (mapv #(fdata/realize-pointers cfg %) libs)
                        libs-by-id (d/index-by :id libs')
                        used       (collect-used-library-components (:data file') libs-by-id)]
                    (mapv #(trim-library-data used %) libs'))
                  libs)

        links   (cond->> (->> (db/query conn :share-link {:file-id file-id})
                              (mapv (fn [row]
                                      (-> row
                                          (update :pages db/decode-pgarray #{})
                                          ;; NOTE: the flags are deprecated but are still present
                                          ;; on the table on old rows. The flags are pgarray and
                                          ;; for avoid decoding it (because they are no longer used
                                          ;; on frontend) we just dissoc the column attribute from
                                          ;; row.
                                          (dissoc :flags)))))
                  (= :share-link (:type perms))
                  (filterv #(= (:id %) share-id)))

        fonts   (db/query conn :team-font-variant
                          {:team-id (:id team)
                           :deleted-at nil})]

    {:users members
     :profiles members
     :fonts fonts
     :project project
     :share-links links
     :libraries libs
     :file file
     :team (assoc team :permissions perms)
     :permissions perms}))

(def schema:get-view-only-bundle
  [:map {:title "get-view-only-bundle"}
   [:file-id ::sm/uuid]
   [:share-id {:optional true} ::sm/uuid]
   [:features {:optional true} ::cfeat/features]])

(sv/defmethod ::get-view-only-bundle
  {::rpc/auth false
   ::doc/added "1.17"
   ::sm/params schema:get-view-only-bundle}
  [system {:keys [::rpc/profile-id file-id share-id] :as params}]
  (db/run! system
           (fn [system]
             (let [perms  (perms/get-file-read-permissions system profile-id file-id share-id)
                   params (-> params
                              (assoc ::perms perms)
                              (assoc :profile-id profile-id))]

               ;; When we have neither profile nor share, we just return a not
               ;; found response to the user.
               (when-not perms
                 (ex/raise :type :not-found
                           :code :object-not-found
                           :hint "object not found"))

               (get-view-only-bundle system params)))))

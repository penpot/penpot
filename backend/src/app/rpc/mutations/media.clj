;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.mutations.media
  (:require
   [app.db :as db]
   [app.media :as media]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.media :as cmd.media]
   [app.rpc.doc :as-alias doc]
   [app.storage :as-alias sto]
   [app.util.services :as sv]
   [clojure.spec.alpha :as s]))

;; --- Create File Media object (upload)

(s/def ::upload-file-media-object ::cmd.media/upload-file-media-object)

(sv/defmethod ::upload-file-media-object
  {::doc/added "1.2"
   ::doc/deprecated "1.18"}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id content] :as params}]
  (let [cfg (update cfg ::sto/storage media/configure-assets-storage)]
    (files/check-edition-permissions! pool profile-id file-id)
    (media/validate-media-type! content)
    (cmd.media/validate-content-size! content)
    (cmd.media/create-file-media-object cfg params)))

;; --- Create File Media Object (from URL)

(s/def ::create-file-media-object-from-url ::cmd.media/create-file-media-object-from-url)

(sv/defmethod ::create-file-media-object-from-url
  {::doc/added "1.3"
   ::doc/deprecated "1.18"}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (let [cfg (update cfg ::sto/storage media/configure-assets-storage)]
    (files/check-edition-permissions! pool profile-id file-id)
    (#'cmd.media/create-file-media-object-from-url cfg params)))

;; --- Clone File Media object (Upload and create from url)

(s/def ::clone-file-media-object ::cmd.media/clone-file-media-object)

(sv/defmethod ::clone-file-media-object
  {::doc/added "1.2"
   ::doc/deprecated "1.18"}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id] :as params}]
  (db/with-atomic [conn pool]
    (files/check-edition-permissions! conn profile-id file-id)
    (-> (assoc cfg :conn conn)
        (cmd.media/clone-file-media-object params))))

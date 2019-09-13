;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.locales.fr)

(defonce locales
  {"ds.projects" "PROJETS"
   "ds.num-projects" ["Nb projets"
                      "%s projet"
                      "%s projets"]
   "ds.project-title" "Vos projets"
   "ds.project-new" "+ Nouveau projet"
   "ds.project-thumbnail.alt" "Titre du projet"

   "ds.ordering" "Trier par"
   "ds.ordering.by-name" "nom"
   "ds.ordering.by-last-update" "dernière mise à jour"
   "ds.ordering.by-creation-date" "date de création"
   "ds.search.placeholder" "Rechercher..."
   "ds.uploaded-at" "Mise en ligne : %s"
   "ds.updated-at" "Mis à jour %s"

   "ds.confirm-title" "Êtes-vous sûr ?"
   "ds.confirm-ok" "Ok"
   "ds.confirm-cancel" "Annuler"

   "ds.multiselect-bar.copy" "Copier"
   "ds.multiselect-bar.copy-to-library" "Copier vers la librairie"
   "ds.multiselect-bar.move" "Déplacer"
   "ds.multiselect-bar.move-to-library" "Déplacer vers la librairie"
   "ds.multiselect-bar.rename" "Renommer"
   "ds.multiselect-bar.delete" "Supprimer"

   "ds.elements" "ÉLÉMENTS"
   "ds.num-elements" ["%s élément"
                      "%s éléments"]

   "ds.icons" "ICÔNES"
   "ds.num-icons" ["Nb icônes"
                   "%s icône"
                   "%s icônes"]
   "ds.your-icons-title" "VOS ICÔNES"
   "ds.store-icons-title" "BOUTIQUE"
   "ds.icons-collection.new" "+ Nouvelle collection"
   "ds.icon-new" "+ Nouvel icône"

   "ds.images" "IMAGES"
   "ds.num-images" ["Nb images"
                    "%s image"
                    "%s images"]
   "ds.your-images-title" "VOS IMAGES"
   "ds.store-images-title" "BOUTIQUE"
   "ds.images-collection.new" "+ Nouvelle librairie"
   "ds.image-new" "+ Nouvelle image"

   "ds.colors" "COULEURS"
   "ds.num-colors" ["Nb couleurs"
                    "%s couleur"
                    "%s couleurs"]
   "ds.your-colors-title" "VOS COULEURS"
   "ds.store-colors-title" "BOUTIQUE"
   "ds.colors-collection.new" "+ Nouvelle librairie"
   "ds.color-new" "+ Nouvelle couleur"
   "ds.color-lightbox.title" "Nouvelle couleur"
   "ds.color-lightbox.add" "+ Ajouter couleur"

   "ds.library-title" "Librairie : "
   "ds.standard-title" "STANDARD"
   "ds.your-libraries-title" "VOS LIBRAIRIES"
   "ds.default-library-title" "Collection sans nom (%s)"

   "ds.project.placeholder" "Nom du nouveau projet"
   "ds.project.new" "Nouveau projet"

   "ds.width" "Largeur"
   "ds.height" "Hauteur"
   "ds.go" "C'est parti !"

   "ds.accept" "Accepter"
   "ds.cancel" "Annuler"

   "ds.settings.icons" "Icônes"
   "ds.settings.element-options" "Options d'élément"
   "ds.settings.draw-tools" "Outils de dessin"
   "ds.settings.sitemap" "Plan du site"
   "ds.settings.layers" "Couches"
   "ds.settings.document-history" "Historique du document"

   "ds.page.placeholder" "Nom de la page"
   "ds.page.new" "Nouvelle page"
   "ds.page.edit" "Éditer la page"

   "ds.history.versions" "Historique"
   "ds.history.pinned" "Épinglés"

   "ds.help.rect" "Boîte (Ctrl + B)"
   "ds.help.circle" "Cercle (Ctrl + E)"
   "ds.help.line" "Ligne (Ctrl + L)"
   "ds.help.text" "Texte"
   "ds.help.path" "Chemin"
   "ds.help.curve" "Courbe"
   "ds.help.ruler" "Règle"

   "ds.user.profile" "Profil"
   "ds.user.password" "Mot de passe"
   "ds.user.notifications" "Notifications"
   "ds.user.exit" "Quitter"

   "header.sitemap" "Plan du site (Ctrl + Maj + M)"
   "header.draw-tools" "Outils de dessin (Ctrl + Maj + S)"
   "header.color-palette" "Palette de couleurs (---)"
   "header.icons" "Icônes (Ctrl + Maj + I)"
   "header.layers" "Couches (Ctrl + Maj + L)"
   "header.element-options" "Options d'élément (Ctrl + Maj + O)"
   "header.document-history" "Historique du document (Ctrl + Maj + H)"
   "header.undo" "Annuler (Ctrl + Z)"
   "header.redo" "Rétablir (Ctrl + Maj + Z)"
   "header.download" "Télécharger (Ctrl + E)"
   "header.image" "Image (Ctrl + I)"
   "header.rules" "Règles"
   "header.grid" "Grille (Ctrl + G)"
   "header.grid-snap" "Coller à la grille"
   "header.align" "Aligner (Ctrl + A)"
   "header.view-mode" "Mode visualisation (Ctrl + P)"

   "element.measures" "Taille, position et rotation"
   "element.fill" "Fond"
   "element.stroke" "Contour"
   "element.text" "Texte"
   "element.interactions" "Interactions"
   "element.page-measures" "Paramètres de la page"
   "element.page-grid-options" "Paramètres de la grille"

   "auth.email-or-username" "adresse email ou nom d'utilisateur"
   "auth.password" "Mot de passe"
   "auth.signin" "Se connecter"
   "auth.forgot-password" "Mot de passe oublié ?"
   "auth.no-account" "Vous n'avez pas de compte ?"
   "auth.message.recovery-token-sent" "Lien de récupération de mot de passe envoyé."
   "auth.message.password-recovered" "Mot de passe récupéré avec succès."

   "register.fullname.placeholder" "Nom complet"
   "register.username.placeholder" "Nom d'utilisateur"
   "register.email.placeholder" "Adresse email"
   "register.password.placeholder" "Mot de passe"
   "register.get-started" "Commencer"
   "register.already-have-account" "Vous avez déjà un compte ?"

   "recovery-request.username-or-email.placeholder" "nom d'utilisateur ou adresse email"
   "recovery-request.recover-password" "Récupérer le mot de passe"
   "recovery-request.go-back" "Retour!"

   "recover.password.placeholder" "Mot de passe"
   "recover.recover-password" "Récupérer le mot de passe"
   "recover.go-back" "Retour!"

   "settings.profile" "PROFIL"
   "settings.password" "MOT DE PASSE"
   "settings.notifications" "NOTIFICATIONS"
   "settings.exit" "QUITTER"

   "settings.profile.profile-saved" "Profil enregistré avec succès !"
   "settings.profile.section-basic-data" "Nom, nom d'utilisateur et adresse email"
   "settings.profile.section-i18n-data" "Langue par défaut"
   "settings.profile.your-name" "Votre nom complet"
   "settings.profile.your-username" "Votre nom d'utilisateur"
   "settings.profile.your-email" "Votre adresse email"

   "settings.choose-color-theme" "Choisissez un thème"
   "settings.profile.light-theme" "Thème Jour"
   "settings.profile.dark-theme" "Thème Nuit"
   "settings.profile.high-contrast-theme" "Thème Contraste élevé"
   "settings.profile.your-avatar" "Votre avatar"

   "settings.password.password-saved" "Mot de passe enregistré avec succès !"
   "settings.password.wrong-old-password" "Ancien mot de passe incorrect"
   "settings.password.change-password" "Changement de mot de passe"
   "settings.password.old-password" "Ancien mot de passe"
   "settings.password.new-password" "Nouveau mot de passe"
   "settings.password.confirm-password" "Confirmez mot de passe"

   "settings.notifications.notifications-saved" "Préférences de notifications enregistrées avec succès !"
   "settings.notifications.prototype-notifications" "Notifications de prototypage"
   "settings.notifications.description" "Obtenez un résumé des modifications apportées aux prototypes à votre adresse email."
   "settings.notifications.none" "Aucune"
   "settings.notifications.every-hour" "Chaque heure"
   "settings.notifications.every-day" "Chaque jour"

   "settings.update-settings" "Mettre à jour les paramètres"

   "history.alert-message" "Vous voyez la version %s"

   "errors.api.form.old-password-not-match" "Ancien mot de passe incorrect"
   "errors.api.form.registration-disabled" "L'enregistrement est actuellement désactivé."
   "errors.api.form.email-already-exists" "L'email est déjà utilisé par un autre utilisateur."
   "errors.api.form.username-already-exists" "Le nom d'utilisateur est déjà utilisé par un autre utilisateur."
   "errors.api.form.user-not-exists" "Le nom d'utilisateur ou l'e-mail ne correspond à aucun utilisateur existant."
   "errors.form.required" "Ce champ est obligatoire"
   "errors.form.string" "Devrait être une chaîne de caractères"
   "errors.form.number" "Nombre invalide"
   "errors.form.integer" "Entier invalide"
   "errors.form.bool" "Devrait être un booléen"
   "errors.form.min-len" "Devrait être supérieur à %s"
   "errors.form.max-len" "Devrait être inférieur à %s"
   "errors.form.color" "Devrait être une couleur valide"
   "errors.form.password-not-match" "Le mot de passe ne correspond pas"
   "errors.auth.unauthorized" "Le nom d'utilisateur ou le mot de passe semble être faux."
   "errors.auth.invalid-recovery-token" "Le jeton de récupération n'est pas valide."
   "errors.profile.update-password" "Erreur lors de la mise à jour du mot de passe, votre ancien mot de passe est probablement incorrect."

   "errors.network" "Impossible de se connecter au serveur principal."
   "errors.generic" "Quelque chose c'est mal passé."
   "errors.conflict" "Conflit sur la sauvegarde des données, actualisez et réessayez."

   })

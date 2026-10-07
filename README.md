# EterResource

Les **mondes ressources** côté **Paper**. Document développeur, à tenir à jour avec le code.
Les serveurs eux-mêmes (création, monde neuf, remplacement au bout de 12 h) sont gérés côté proxy par
**EterVelocityResource**.

Un seul jar, deux rôles selon le serveur :

- **Accès** (lobbys, survies… partout) : `/ressource` pour acheter du temps, utiliser une clé et partir.
- **Monde ressource** (server-name qui commence par `world-server-prefix`, `ressource` par défaut, comme le
  `name-prefix` d'EterVelocityResource) : préparation du monde, temps qui s'écoule, bonus de métier.

## Prérequis

- **EterLib 1.7.0+** (`depend`) : base, langues et textes communs, menus (cadre, bouton Retour, Dialogs), joueurs par
  serveur (`countByServer`), annuaire des joueurs (`find`), envoi vers un serveur (`getTeleports().connect`).
- **Vault** + **EterEconomy** (`softdepend`) pour l'achat ; sans économie, seules les clés marchent.
- **EterMarket** sur les mondes ressources, pour les bonus de métier (table `etermarket_job_members`, lue seulement)
  et pour que les quêtes de métier comptent aussi là-bas.
- **EterVelocityLobby** : un joueur expulsé d'un monde ressource (temps écoulé, monde pas prêt) est renvoyé au lobby,
  avec la raison.

## Modules

- **`access`** : table `eterresource_access` (uuid, `remaining_ms`, `key_count`), commune au réseau. Chaque changement
  est UNE requête qui vérifie elle-même ses conditions (clé disponible, plafond `max-minutes`) : un double clic ou deux
  serveurs en même temps ne donnent jamais de temps en trop. Achat (`slot-price` pour `slot-minutes`) : le temps est
  ajouté d'abord, puis retiré si le paiement échoue. Menu `/ressource` (tête du joueur avec temps et clés, acheter
  avec confirmation en Dialog, utiliser une clé, partir sur le monde ouvert le moins rempli).
  `/eterresource givekey|givetime|info <joueur>` (staff et console, joueur hors ligne possible) : pour EterReward
  ou un dédommagement ; `givetime` ignore le plafond.
- **`world`** :
  - `WorldDirectory` (table `eterresource_worlds`) : chaque monde y écrit son signe de vie toutes les 10 s et s'il est
    prêt. Sont proposés les mondes prêts vus depuis moins de 30 s et, si la table de l'orchestrateur
    (`eterresource_servers`) existe, seulement ceux qu'il ne vide pas (`state = ACTIVE`).
  - `WorldSetup` : au démarrage, sur toutes les dimensions, `KEEP_INVENTORY` et pas de `PVP` (règles de jeu : flèches
    et potions comprises), bordure centrée sur le spawn (`world.border`), puis prégénération autour du spawn
    (`world.pregenerate`, 8 chunks à la fois, du centre vers l'extérieur). Le monde n'est « prêt » qu'après.
  - `Sessions` : à l'arrivée il faut un monde prêt et du temps, sinon expulsion. Le temps s'écoule chaque seconde
    (en mémoire) ; le consommé part en base toutes les 30 s, au départ et à l'arrêt du serveur (les joueurs quittent
    APRÈS l'arrêt des plugins), et le temps restant est relu au passage (un achat fait sur le monde compte tout de
    suite). Rappels à 15, 5 et 1 min ; compte à rebours dans l'action bar les 5 dernières minutes.
    `eterresource.bypass.time` : le staff entre sans temps et n'en consomme pas.
- **`bonus`** : métier relu à l'arrivée puis chaque minute ; `jobs.<métier>` dans config.yml (effets, abattage
  d'arbre, récoltes multipliées, butin des créatures). Les effets durent 15 s, sans particules, renouvelés toutes les
  5 s, et sont retirés au départ en `LOWEST`, AVANT qu'EterSync (`MONITOR`) n'enregistre les effets du joueur : ils ne le
  suivent jamais sur un autre serveur. Seuls nos effets sont retirés (ambiants, même niveau, 15 s au plus) : une potion
  bue reste. L'abattage (64 bûches au plus, hache, pas accroupi) use la hache d'un point par bûche ; seule la première
  bûche compte pour les quêtes.

## Permissions

| Permission | Par défaut | Rôle |
|---|---|---|
| `eterresource.use` | tous | `/ressource` |
| `eterresource.bypass.time` | op | Monde ressource sans temps |
| `eterresource.admin` | op | Tout, dont `/eterresource` |

## Technique

- Le temps de l'action bar et des messages passe par `EterLib#formatDuration`.
- Pas de config à garder sur un monde ressource : les valeurs du jar suffisent (le modèle n'a pas de dossier
  `plugins/EterResource`). Si on change `world-server-prefix`, changer aussi le `name-prefix` d'EterVelocityResource.

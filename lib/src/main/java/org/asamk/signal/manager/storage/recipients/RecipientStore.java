package org.asamk.signal.manager.storage.recipients;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.asamk.signal.manager.api.Pair;
import org.asamk.signal.manager.api.UnregisteredRecipientException;
import org.asamk.signal.manager.storage.Utils;
import org.asamk.signal.manager.storage.contacts.ContactsStore;
import org.asamk.signal.manager.storage.profiles.ProfileStore;
import org.signal.zkgroup.InvalidInputException;
import org.signal.zkgroup.profiles.ProfileKey;
import org.signal.zkgroup.profiles.ProfileKeyCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.util.UuidUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class RecipientStore implements RecipientResolver, ContactsStore, ProfileStore {

    private final static Logger logger = LoggerFactory.getLogger(RecipientStore.class);

    private final ObjectMapper objectMapper;
    private final File file;
    private final RecipientMergeHandler recipientMergeHandler;

    private final Map<RecipientId, Recipient> recipients;
    private final Map<Long, Long> recipientsMerged = new HashMap<>();

    private long lastId;

    public static RecipientStore load(File file, RecipientMergeHandler recipientMergeHandler) throws IOException {
        final var objectMapper = Utils.createStorageObjectMapper();
        try (var inputStream = new FileInputStream(file)) {
            final var storage = objectMapper.readValue(inputStream, Storage.class);

            final var recipientStore = new RecipientStore(objectMapper,
                    file,
                    recipientMergeHandler,
                    new HashMap<>(),
                    storage.lastId);
            final var recipients = storage.recipients.stream().map(r -> {
                final var recipientId = new RecipientId(r.id, recipientStore);
                final var address = new RecipientAddress(Optional.ofNullable(r.uuid).map(UuidUtil::parseOrThrow),
                        Optional.ofNullable(r.number));

                Contact contact = null;
                if (r.contact != null) {
                    contact = new Contact(r.contact.name,
                            r.contact.color,
                            r.contact.messageExpirationTime,
                            r.contact.blocked,
                            r.contact.archived);
                }

                ProfileKey profileKey = null;
                if (r.profileKey != null) {
                    try {
                        profileKey = new ProfileKey(Base64.getDecoder().decode(r.profileKey));
                    } catch (InvalidInputException ignored) {
                    }
                }

                ProfileKeyCredential profileKeyCredential = null;
                if (r.profileKeyCredential != null) {
                    try {
                        profileKeyCredential = new ProfileKeyCredential(Base64.getDecoder()
                                .decode(r.profileKeyCredential));
                    } catch (Throwable ignored) {
                    }
                }

                Profile profile = null;
                if (r.profile != null) {
                    profile = new Profile(r.profile.lastUpdateTimestamp,
                            r.profile.givenName,
                            r.profile.familyName,
                            r.profile.about,
                            r.profile.aboutEmoji,
                            r.profile.avatarUrlPath,
                            Profile.UnidentifiedAccessMode.valueOfOrUnknown(r.profile.unidentifiedAccessMode),
                            r.profile.capabilities.stream()
                                    .map(Profile.Capability::valueOfOrNull)
                                    .filter(Objects::nonNull)
                                    .collect(Collectors.toSet()));
                }

                return new Recipient(recipientId, address, contact, profileKey, profileKeyCredential, profile);
            }).collect(Collectors.toMap(Recipient::getRecipientId, r -> r));

            recipientStore.addRecipients(recipients);

            return recipientStore;
        } catch (FileNotFoundException e) {
            logger.trace("Creating new recipient store.");
            return new RecipientStore(objectMapper, file, recipientMergeHandler, new HashMap<>(), 0);
        }
    }

    private RecipientStore(
            final ObjectMapper objectMapper,
            final File file,
            final RecipientMergeHandler recipientMergeHandler,
            final Map<RecipientId, Recipient> recipients,
            final long lastId
    ) {
        this.objectMapper = objectMapper;
        this.file = file;
        this.recipientMergeHandler = recipientMergeHandler;
        this.recipients = recipients;
        this.lastId = lastId;
    }

    public RecipientAddress resolveRecipientAddress(RecipientId recipientId) {
        synchronized (recipients) {
            return getRecipient(recipientId).getAddress();
        }
    }

    public Recipient getRecipient(RecipientId recipientId) {
        synchronized (recipients) {
            return recipients.get(recipientId);
        }
    }

    @Override
    public RecipientId resolveRecipient(ACI aci) {
        return resolveRecipient(new RecipientAddress(aci.uuid()), false);
    }

    @Override
    public RecipientId resolveRecipient(final long recipientId) {
        final var recipient = getRecipient(new RecipientId(recipientId, this));
        return recipient == null ? null : recipient.getRecipientId();
    }

    @Override
    public RecipientId resolveRecipient(final String identifier) {
        return resolveRecipient(Utils.getRecipientAddressFromIdentifier(identifier), false);
    }

    public RecipientId resolveRecipient(
            final String number, Supplier<ACI> aciSupplier
    ) throws UnregisteredRecipientException {
        final Optional<Recipient> byNumber;
        synchronized (recipients) {
            byNumber = findByNumberLocked(number);
        }
        if (byNumber.isEmpty() || byNumber.get().getAddress().uuid().isEmpty()) {
            final var aci = aciSupplier.get();
            if (aci == null) {
                throw new UnregisteredRecipientException(new RecipientAddress(null, number));
            }

            return resolveRecipient(new RecipientAddress(aci.uuid(), number), false);
        }
        return byNumber.get().getRecipientId();
    }

    public RecipientId resolveRecipient(RecipientAddress address) {
        return resolveRecipient(address, false);
    }

    @Override
    public RecipientId resolveRecipient(final SignalServiceAddress address) {
        return resolveRecipient(new RecipientAddress(address), false);
    }

    public RecipientId resolveRecipientTrusted(RecipientAddress address) {
        return resolveRecipient(address, true);
    }

    public RecipientId resolveRecipientTrusted(SignalServiceAddress address) {
        return resolveRecipient(new RecipientAddress(address), true);
    }

    public List<RecipientId> resolveRecipientsTrusted(List<RecipientAddress> addresses) {
        final List<RecipientId> recipientIds;
        final List<Pair<RecipientId, RecipientId>> toBeMerged = new ArrayList<>();
        synchronized (recipients) {
            recipientIds = addresses.stream().map(address -> {
                final var pair = resolveRecipientLocked(address, true);
                if (pair.second().isPresent()) {
                    toBeMerged.add(new Pair<>(pair.first(), pair.second().get()));
                }
                return pair.first();
            }).toList();
        }
        for (var pair : toBeMerged) {
            recipientMergeHandler.mergeRecipients(pair.first(), pair.second());
        }
        return recipientIds;
    }

    @Override
    public void storeContact(RecipientId recipientId, final Contact contact) {
        synchronized (recipients) {
            final var recipient = recipients.get(recipientId);
            storeRecipientLocked(recipientId, Recipient.newBuilder(recipient).withContact(contact).build());
        }
    }

    @Override
    public Contact getContact(RecipientId recipientId) {
        final var recipient = getRecipient(recipientId);
        return recipient == null ? null : recipient.getContact();
    }

    @Override
    public List<Pair<RecipientId, Contact>> getContacts() {
        return recipients.entrySet()
                .stream()
                .filter(e -> e.getValue().getContact() != null)
                .map(e -> new Pair<>(e.getKey(), e.getValue().getContact()))
                .toList();
    }

    @Override
    public void deleteContact(RecipientId recipientId) {
        synchronized (recipients) {
            final var recipient = recipients.get(recipientId);
            storeRecipientLocked(recipientId, Recipient.newBuilder(recipient).withContact(null).build());
        }
    }

    public void deleteRecipientData(RecipientId recipientId) {
        synchronized (recipients) {
            logger.debug("Deleting recipient data for {}", recipientId);
            final var recipient = recipients.get(recipientId);
            storeRecipientLocked(recipientId,
                    Recipient.newBuilder()
                            .withRecipientId(recipientId)
                            .withAddress(new RecipientAddress(recipient.getAddress().uuid().orElse(null)))
                            .build());
        }
    }

    @Override
    public Profile getProfile(final RecipientId recipientId) {
        final var recipient = getRecipient(recipientId);
        return recipient == null ? null : recipient.getProfile();
    }

    @Override
    public ProfileKey getProfileKey(final RecipientId recipientId) {
        final var recipient = getRecipient(recipientId);
        return recipient == null ? null : recipient.getProfileKey();
    }

    @Override
    public ProfileKeyCredential getProfileKeyCredential(final RecipientId recipientId) {
        final var recipient = getRecipient(recipientId);
        return recipient == null ? null : recipient.getProfileKeyCredential();
    }

    @Override
    public void storeProfile(RecipientId recipientId, final Profile profile) {
        synchronized (recipients) {
            final var recipient = recipients.get(recipientId);
            storeRecipientLocked(recipientId, Recipient.newBuilder(recipient).withProfile(profile).build());
        }
    }

    @Override
    public void storeProfileKey(RecipientId recipientId, final ProfileKey profileKey) {
        synchronized (recipients) {
            final var recipient = recipients.get(recipientId);
            if (profileKey != null && profileKey.equals(recipient.getProfileKey())) {
                return;
            }

            final var newRecipient = Recipient.newBuilder(recipient)
                    .withProfileKey(profileKey)
                    .withProfileKeyCredential(null)
                    .withProfile(recipient.getProfile() == null
                            ? null
                            : Profile.newBuilder(recipient.getProfile()).withLastUpdateTimestamp(0).build())
                    .build();
            storeRecipientLocked(recipientId, newRecipient);
        }
    }

    @Override
    public void storeProfileKeyCredential(RecipientId recipientId, final ProfileKeyCredential profileKeyCredential) {
        synchronized (recipients) {
            final var recipient = recipients.get(recipientId);
            storeRecipientLocked(recipientId,
                    Recipient.newBuilder(recipient).withProfileKeyCredential(profileKeyCredential).build());
        }
    }

    public boolean isEmpty() {
        synchronized (recipients) {
            return recipients.isEmpty();
        }
    }

    private void addRecipients(final Map<RecipientId, Recipient> recipients) {
        this.recipients.putAll(recipients);
    }

    /**
     * @param isHighTrust true, if the number/uuid connection was obtained from a trusted source.
     *                    Has no effect, if the address contains only a number or a uuid.
     */
    private RecipientId resolveRecipient(RecipientAddress address, boolean isHighTrust) {
        final Pair<RecipientId, Optional<RecipientId>> pair;
        synchronized (recipients) {
            pair = resolveRecipientLocked(address, isHighTrust);
        }

        if (pair.second().isPresent()) {
            recipientMergeHandler.mergeRecipients(pair.first(), pair.second().get());
        }
        return pair.first();
    }

    private Pair<RecipientId, Optional<RecipientId>> resolveRecipientLocked(
            RecipientAddress address, boolean isHighTrust
    ) {
        final var byNumber = address.number().isEmpty()
                ? Optional.<Recipient>empty()
                : findByNumberLocked(address.number().get());
        final var byUuid = address.uuid().isEmpty()
                ? Optional.<Recipient>empty()
                : findByUuidLocked(address.uuid().get());

        if (byNumber.isEmpty() && byUuid.isEmpty()) {
            logger.debug("Got new recipient, both uuid and number are unknown");

            if (isHighTrust || address.uuid().isEmpty() || address.number().isEmpty()) {
                return new Pair<>(addNewRecipientLocked(address), Optional.empty());
            }

            return new Pair<>(addNewRecipientLocked(new RecipientAddress(address.uuid().get())), Optional.empty());
        }

        if (!isHighTrust || address.uuid().isEmpty() || address.number().isEmpty() || byNumber.equals(byUuid)) {
            return new Pair<>(byUuid.or(() -> byNumber).map(Recipient::getRecipientId).get(), Optional.empty());
        }

        if (byNumber.isEmpty()) {
            logger.debug("Got recipient {} existing with uuid, updating with high trust number",
                    byUuid.get().getRecipientId());
            updateRecipientAddressLocked(byUuid.get().getRecipientId(), address);
            return new Pair<>(byUuid.get().getRecipientId(), Optional.empty());
        }

        final var byNumberRecipient = byNumber.get();

        if (byUuid.isEmpty()) {
            if (byNumberRecipient.getAddress().uuid().isPresent()) {
                logger.debug(
                        "Got recipient {} existing with number, but different uuid, so stripping its number and adding new recipient",
                        byNumberRecipient.getRecipientId());

                updateRecipientAddressLocked(byNumberRecipient.getRecipientId(),
                        new RecipientAddress(byNumberRecipient.getAddress().uuid().get()));
                return new Pair<>(addNewRecipientLocked(address), Optional.empty());
            }

            logger.debug("Got recipient {} existing with number and no uuid, updating with high trust uuid",
                    byNumberRecipient.getRecipientId());
            updateRecipientAddressLocked(byNumberRecipient.getRecipientId(), address);
            return new Pair<>(byNumberRecipient.getRecipientId(), Optional.empty());
        }

        final var byUuidRecipient = byUuid.get();

        if (byNumberRecipient.getAddress().uuid().isPresent()) {
            logger.debug(
                    "Got separate recipients for high trust number {} and uuid {}, recipient for number has different uuid, so stripping its number",
                    byNumberRecipient.getRecipientId(),
                    byUuidRecipient.getRecipientId());

            updateRecipientAddressLocked(byNumberRecipient.getRecipientId(),
                    new RecipientAddress(byNumberRecipient.getAddress().uuid().get()));
            updateRecipientAddressLocked(byUuidRecipient.getRecipientId(), address);
            return new Pair<>(byUuidRecipient.getRecipientId(), Optional.empty());
        }

        logger.debug("Got separate recipients for high trust number {} and uuid {}, need to merge them",
                byNumberRecipient.getRecipientId(),
                byUuidRecipient.getRecipientId());
        updateRecipientAddressLocked(byUuidRecipient.getRecipientId(), address);
        // Create a fixed RecipientId that won't update its id after merge
        final var toBeMergedRecipientId = new RecipientId(byNumberRecipient.getRecipientId().id(), null);
        mergeRecipientsLocked(byUuidRecipient.getRecipientId(), toBeMergedRecipientId);
        return new Pair<>(byUuidRecipient.getRecipientId(), Optional.of(toBeMergedRecipientId));
    }

    private RecipientId addNewRecipientLocked(final RecipientAddress address) {
        final var nextRecipientId = nextIdLocked();
        logger.debug("Adding new recipient {} with address {}", nextRecipientId, address);
        storeRecipientLocked(nextRecipientId, new Recipient(nextRecipientId, address, null, null, null, null));
        return nextRecipientId;
    }

    private void updateRecipientAddressLocked(RecipientId recipientId, final RecipientAddress address) {
        final var recipient = recipients.get(recipientId);
        storeRecipientLocked(recipientId, Recipient.newBuilder(recipient).withAddress(address).build());
    }

    long getActualRecipientId(long recipientId) {
        while (recipientsMerged.containsKey(recipientId)) {
            final var newRecipientId = recipientsMerged.get(recipientId);
            logger.debug("Using {} instead of {}, because recipients have been merged", newRecipientId, recipientId);
            recipientId = newRecipientId;
        }
        return recipientId;
    }

    private void storeRecipientLocked(final RecipientId recipientId, final Recipient recipient) {
        final var existingRecipient = recipients.get(recipientId);
        if (existingRecipient == null || !existingRecipient.equals(recipient)) {
            recipients.put(recipientId, recipient);
            saveLocked();
        }
    }

    private void mergeRecipientsLocked(RecipientId recipientId, RecipientId toBeMergedRecipientId) {
        final var recipient = recipients.get(recipientId);
        final var toBeMergedRecipient = recipients.get(toBeMergedRecipientId);
        recipients.put(recipientId,
                new Recipient(recipientId,
                        recipient.getAddress(),
                        recipient.getContact() != null ? recipient.getContact() : toBeMergedRecipient.getContact(),
                        recipient.getProfileKey() != null
                                ? recipient.getProfileKey()
                                : toBeMergedRecipient.getProfileKey(),
                        recipient.getProfileKeyCredential() != null
                                ? recipient.getProfileKeyCredential()
                                : toBeMergedRecipient.getProfileKeyCredential(),
                        recipient.getProfile() != null ? recipient.getProfile() : toBeMergedRecipient.getProfile()));
        recipients.remove(toBeMergedRecipientId);
        recipientsMerged.put(toBeMergedRecipientId.id(), recipientId.id());
        saveLocked();
    }

    private Optional<Recipient> findByNumberLocked(final String number) {
        return recipients.entrySet()
                .stream()
                .filter(entry -> entry.getValue().getAddress().number().isPresent() && number.equals(entry.getValue()
                        .getAddress()
                        .number()
                        .get()))
                .findFirst()
                .map(Map.Entry::getValue);
    }

    private Optional<Recipient> findByUuidLocked(final UUID uuid) {
        return recipients.entrySet()
                .stream()
                .filter(entry -> entry.getValue().getAddress().uuid().isPresent() && uuid.equals(entry.getValue()
                        .getAddress()
                        .uuid()
                        .get()))
                .findFirst()
                .map(Map.Entry::getValue);
    }

    private RecipientId nextIdLocked() {
        return new RecipientId(++this.lastId, this);
    }

    private void saveLocked() {
        final var base64 = Base64.getEncoder();
        var storage = new Storage(recipients.entrySet().stream().map(pair -> {
            final var recipient = pair.getValue();
            final var contact = recipient.getContact() == null
                    ? null
                    : new Storage.Recipient.Contact(recipient.getContact().getName(),
                            recipient.getContact().getColor(),
                            recipient.getContact().getMessageExpirationTime(),
                            recipient.getContact().isBlocked(),
                            recipient.getContact().isArchived());
            final var profile = recipient.getProfile() == null
                    ? null
                    : new Storage.Recipient.Profile(recipient.getProfile().getLastUpdateTimestamp(),
                            recipient.getProfile().getGivenName(),
                            recipient.getProfile().getFamilyName(),
                            recipient.getProfile().getAbout(),
                            recipient.getProfile().getAboutEmoji(),
                            recipient.getProfile().getAvatarUrlPath(),
                            recipient.getProfile().getUnidentifiedAccessMode().name(),
                            recipient.getProfile()
                                    .getCapabilities()
                                    .stream()
                                    .map(Enum::name)
                                    .collect(Collectors.toSet()));
            return new Storage.Recipient(pair.getKey().id(),
                    recipient.getAddress().number().orElse(null),
                    recipient.getAddress().uuid().map(UUID::toString).orElse(null),
                    recipient.getProfileKey() == null
                            ? null
                            : base64.encodeToString(recipient.getProfileKey().serialize()),
                    recipient.getProfileKeyCredential() == null
                            ? null
                            : base64.encodeToString(recipient.getProfileKeyCredential().serialize()),
                    contact,
                    profile);
        }).toList(), lastId);

        // Write to memory first to prevent corrupting the file in case of serialization errors
        try (var inMemoryOutput = new ByteArrayOutputStream()) {
            objectMapper.writeValue(inMemoryOutput, storage);

            var input = new ByteArrayInputStream(inMemoryOutput.toByteArray());
            try (var outputStream = new FileOutputStream(file)) {
                input.transferTo(outputStream);
            }
        } catch (Exception e) {
            logger.error("Error saving recipient store file: {}", e.getMessage());
        }
    }

    private record Storage(List<Recipient> recipients, long lastId) {

        private record Recipient(
                long id,
                String number,
                String uuid,
                String profileKey,
                String profileKeyCredential,
                Storage.Recipient.Contact contact,
                Storage.Recipient.Profile profile
        ) {

            private record Contact(
                    String name, String color, int messageExpirationTime, boolean blocked, boolean archived
            ) {}

            private record Profile(
                    long lastUpdateTimestamp,
                    String givenName,
                    String familyName,
                    String about,
                    String aboutEmoji,
                    String avatarUrlPath,
                    String unidentifiedAccessMode,
                    Set<String> capabilities
            ) {}
        }
    }

    public interface RecipientMergeHandler {

        void mergeRecipients(RecipientId recipientId, RecipientId toBeMergedRecipientId);
    }
}

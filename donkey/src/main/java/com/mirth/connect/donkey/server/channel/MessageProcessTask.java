/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL
 * license a copy of which has been included with this distribution in
 * the LICENSE.txt file.
 */

package com.mirth.connect.donkey.server.channel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.Callable;

import org.apache.commons.codec.binary.StringUtils;
import org.apache.log4j.Logger;

import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.ContentType;
import com.mirth.connect.donkey.model.message.Message;
import com.mirth.connect.donkey.model.message.MessageContent;
import com.mirth.connect.donkey.model.message.RawMessage;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.model.message.attachment.Attachment;
import com.mirth.connect.donkey.model.message.attachment.AttachmentHandler;
import com.mirth.connect.donkey.server.controllers.MessageController;
import com.mirth.connect.donkey.server.data.DonkeyDao;
import com.mirth.connect.donkey.server.data.DonkeyDaoFactory;
import com.mirth.connect.donkey.util.Base64Util;
import com.mirth.connect.donkey.util.ThreadUtils;

final class MessageProcessTask implements Callable<MessageResponse> {
    private RawMessage rawMessage;
    private Channel channel;
    private StorageSettings storageSettings;
    private DonkeyDaoFactory sourceDaoFactory;
    private ResponseSelector responseSelector;
    private boolean waitForDestinations;
    private Logger logger = Logger.getLogger(getClass());
    private MessageResponse messageResponse;
    private boolean messagePersisted = false;

    MessageProcessTask(RawMessage rawMessage, Channel channel) {
        this.rawMessage = rawMessage;
        this.channel = channel;
        this.storageSettings = channel.getSourceConnector().getStorageSettings();
        this.sourceDaoFactory = channel.getSourceConnector().getDaoFactory();
        this.waitForDestinations = channel.getSourceConnector().isWaitForDestinations();
        this.responseSelector = channel.getResponseSelector();
    }

    public boolean isMessagePersisted() {
        return messagePersisted;
    }

    @Override
    public MessageResponse call() throws Exception {
        DonkeyDao dao = null;
        try {
            if (waitForDestinations) {
                synchronized (channel.getResponseSent()) {
                    while (!channel.getResponseSent().get()) {
                        channel.getResponseSent().wait();
                    }

                    channel.getResponseSent().set(false);
                }
            }

            /*
             * TRANSACTION: Create Raw Message
             * - create a source connector message from the raw message and set the
             * status as RECEIVED
             * - store attachments
             */
            dao = sourceDaoFactory.getDao();
            ConnectorMessage sourceMessage = createAndStoreSourceMessage(dao, rawMessage);
            Message processedMessage = null;
            Response response = null;
            ThreadUtils.checkInterruptedStatus();

            if (waitForDestinations) {
                dao.commit();
                messagePersisted = true;
                dao.close();

                processedMessage = channel.process(sourceMessage, false);
                response = responseSelector.getResponse(processedMessage);
            } else {
                // Block other threads from adding to the source queue until both the current commit and queue addition finishes
                synchronized (channel.getSourceQueue()) {
                    dao.commit();
                    messagePersisted = true;
                    dao.close();
                    channel.queue(sourceMessage);
                }
            }

            messageResponse = new MessageResponse(sourceMessage.getMessageId(), response, waitForDestinations, processedMessage);

            return messageResponse;
        } catch (RuntimeException e) {
            //TODO enable channel restart after it has been updated. Currently does not work
//            Donkey.getInstance().restartChannel(channel.getChannelId(), true);
            throw new ChannelException(messagePersisted, true, e);
        } finally {
            if (dao != null && !dao.isClosed()) {
                dao.close();
            }
        }
    }

    private ConnectorMessage createAndStoreSourceMessage(DonkeyDao dao, RawMessage rawMessage) throws InterruptedException {
        ThreadUtils.checkInterruptedStatus();
        Long messageId;
        Calendar dateCreated;
        String channelId = channel.getChannelId();

        if (rawMessage.getMessageIdToOverwrite() == null) {
            Message message = MessageController.getInstance().createNewMessage(channelId, channel.getServerId());
            messageId = message.getMessageId();
            dateCreated = message.getDateCreated();
            dao.insertMessage(message);
        } else {
            List<Integer> metaDataIds = new ArrayList<Integer>();
            metaDataIds.addAll(rawMessage.getDestinationMetaDataIds());
            metaDataIds.add(0);
            dao.deleteConnectorMessages(channelId, rawMessage.getMessageIdToOverwrite(), metaDataIds, true);
            messageId = rawMessage.getMessageIdToOverwrite();
            dateCreated = Calendar.getInstance();
        }

        ConnectorMessage sourceMessage = new ConnectorMessage(channelId, messageId, 0, channel.getServerId(), dateCreated, Status.RECEIVED);
        sourceMessage.setRaw(new MessageContent(channelId, messageId, 0, ContentType.RAW, null, false));

        if (rawMessage.getChannelMap() != null) {
            sourceMessage.setChannelMap(rawMessage.getChannelMap());
        }

        AttachmentHandler attachmentHandler = channel.getAttachmentHandler();

        if (attachmentHandler != null) {
            ThreadUtils.checkInterruptedStatus();

            try {
                if (rawMessage.isBinary()) {
                    attachmentHandler.initialize(rawMessage.getRawBytes(), channel);
                } else {
                    attachmentHandler.initialize(rawMessage.getRawData(), channel);
                }

                // Free up the memory of the raw message since it is no longer being used
                rawMessage.clearMessage();

                Attachment attachment;
                while ((attachment = attachmentHandler.nextAttachment()) != null) {
                    ThreadUtils.checkInterruptedStatus();

                    if (storageSettings.isStoreAttachments()) {
                        dao.insertMessageAttachment(channelId, messageId, attachment);
                    }
                }

                String replacedMessage = attachmentHandler.shutdown();

                sourceMessage.getRaw().setContent(replacedMessage);
            } catch (Exception e) {
                logger.error("Error processing attachments for channel " + channelId + ". " + e.getMessage());
            }
        } else {
            if (rawMessage.isBinary()) {
                ThreadUtils.checkInterruptedStatus();

                try {
                    byte[] rawBytes = Base64Util.encodeBase64(rawMessage.getRawBytes());
                    rawMessage.clearMessage();
                    sourceMessage.getRaw().setContent(StringUtils.newStringUsAscii(rawBytes));
                } catch (IOException e) {
                    logger.error("Error processing binary data for channel " + channelId + ". " + e.getMessage());
                }

            } else {
                sourceMessage.getRaw().setContent(rawMessage.getRawData());
                rawMessage.clearMessage();
            }
        }

        ThreadUtils.checkInterruptedStatus();
        dao.insertConnectorMessage(sourceMessage, storageSettings.isStoreMaps());

        if (storageSettings.isStoreRaw()) {
            ThreadUtils.checkInterruptedStatus();
            dao.insertMessageContent(sourceMessage.getRaw());
        }

        return sourceMessage;
    }
}
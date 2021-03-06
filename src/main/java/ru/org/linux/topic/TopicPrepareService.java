/*
 * Copyright 1998-2015 Linux.org.ru
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package ru.org.linux.topic;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.org.linux.edithistory.EditInfoSummary;
import ru.org.linux.gallery.Image;
import ru.org.linux.gallery.ImageDao;
import ru.org.linux.group.Group;
import ru.org.linux.group.GroupDao;
import ru.org.linux.group.GroupPermissionService;
import ru.org.linux.poll.Poll;
import ru.org.linux.poll.PollNotFoundException;
import ru.org.linux.poll.PollPrepareService;
import ru.org.linux.poll.PreparedPoll;
import ru.org.linux.section.Section;
import ru.org.linux.section.SectionService;
import ru.org.linux.site.DeleteInfo;
import ru.org.linux.spring.SiteConfig;
import ru.org.linux.spring.dao.DeleteInfoDao;
import ru.org.linux.spring.dao.MessageText;
import ru.org.linux.spring.dao.MsgbaseDao;
import ru.org.linux.tag.TagRef;
import ru.org.linux.user.*;
import ru.org.linux.util.BadImageException;
import ru.org.linux.util.LorURL;
import ru.org.linux.util.bbcode.LorCodeService;
import ru.org.linux.util.image.ImageInfo;
import scala.Option;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class TopicPrepareService {
  private static final Logger logger = LoggerFactory.getLogger(TopicPrepareService.class);

  @Autowired
  private GroupDao groupDao;

  @Autowired
  private UserDao userDao;

  @Autowired
  private SectionService sectionService;

  @Autowired
  private DeleteInfoDao deleteInfoDao;

  @Autowired
  private PollPrepareService pollPrepareService;

  @Autowired
  private LorCodeService lorCodeService;

  @Autowired
  private SiteConfig siteConfig;

  @Autowired
  private TopicPermissionService topicPermissionService;
  
  @Autowired
  private GroupPermissionService groupPermissionService;
  
  @Autowired
  private MsgbaseDao msgbaseDao;

  @Autowired
  private ImageDao imageDao;

  @Autowired
  private UserService userService;

  @Autowired
  private TopicTagService topicTagService;

  @Autowired
  private RemarkDao remarkDao;
  
  public PreparedTopic prepareTopic(Topic message, boolean secure, User user) {
    return prepareMessage(
            message,
            topicTagService.getTagRefs(message),
            false,
            null,
            secure,
            user,
            msgbaseDao.getMessageText(message.getId()),
            null
    );
  }

  public PreparedTopic prepareTopic(Topic message, List<TagRef> tags, boolean secure, User user, MessageText text) {
    return prepareMessage(
            message,
            tags,
            false,
            null,
            secure,
            user,
            text,
            null
    );
  }

  public PreparedTopic prepareTopicPreview(
          Topic message,
          List<TagRef> tags,
          Poll newPoll,
          boolean secure,
          String text,
          Image image
  ) {
    return prepareMessage(
            message,
            tags,
            false,
            newPoll!=null?pollPrepareService.preparePollPreview(newPoll):null,
            secure,
            null,
            new MessageText(text, true),
            image
    );
  }

  public PreparedEditInfoSummary prepareEditInfo(EditInfoSummary editInfo) {
    String lastEditor = userDao.getUserCached(editInfo.editor()).getNick();
    int editCount = editInfo.editCount();
    Date lastEditDate = editInfo.editdate();
    return PreparedEditInfoSummary.apply(lastEditor, editCount, lastEditDate);
  }

  /**
   * Функция подготовки топика
   * @param message топик
   * @param tags список тэгов
   * @param minimizeCut сворачивать ли cut
   * @param poll опрос к топику
   * @param secure является ли соединение https
   * @param user пользователь
   * @return подготовленный топик
   */
  private PreparedTopic prepareMessage(
          Topic message, 
          List<TagRef> tags,
          boolean minimizeCut, 
          PreparedPoll poll,
          boolean secure, 
          User user,
          MessageText text,
          @Nullable Image image) {
    try {
      Group group = groupDao.getGroup(message.getGroupId());
      User author = userDao.getUserCached(message.getUid());
      Section section = sectionService.getSection(message.getSectionId());

      DeleteInfo deleteInfo;
      User deleteUser;
      if (message.isDeleted()) {
        deleteInfo = deleteInfoDao.getDeleteInfo(message.getId());

        if (deleteInfo!=null) {
          deleteUser = userDao.getUserCached(deleteInfo.getUserid());
        } else {
          deleteUser = null;
        }
      } else {
        deleteInfo = null;
        deleteUser = null;
      }

      PreparedPoll preparedPoll;

      if (section.isPollPostAllowed()) {
        if (poll==null) {
          preparedPoll = pollPrepareService.preparePoll(message, user);
        } else {
          preparedPoll = poll;
        }
      } else {
        preparedPoll = null;
      }

      User commiter;

      if (message.getCommitby()!=0) {
        commiter = userDao.getUserCached(message.getCommitby());
      } else {
        commiter = null;
      }

      String processedMessage;

      if (text.isLorcode()) {
        if (minimizeCut) {
          String url = siteConfig.getMainUrl() + message.getLink();
          processedMessage = lorCodeService.parseTopicWithMinimizedCut(
                  text.getText(),
                  url,
                  secure,
                  ! topicPermissionService.followInTopic(message, author)
          );
        } else {
          processedMessage = lorCodeService.parseTopic(text.getText(), secure, ! topicPermissionService.followInTopic(message, author));
        }
      } else {
        processedMessage = "<p>" + text.getText();
      }

      PreparedImage preparedImage = null;

      if (section.isImagepost() || section.isImageAllowed()) {
        if (message.getId()!=0) {
          image = imageDao.imageForTopic(message);
        }

        if (image != null) {
          preparedImage = prepareImage(image, secure);
        }
      }
      Remark remark = null;
      if (user != null ){
        Option<Remark> remarkOption = remarkDao.getRemark(user, author);

        if (remarkOption.isDefined()) {
          remark = remarkOption.get();
        }
      }

      int postscore = topicPermissionService.getPostscore(group, message);

      return new PreparedTopic(
              message, 
              author, 
              deleteInfo, 
              deleteUser, 
              processedMessage,
              preparedPoll,
              commiter, 
              tags,
              group,
              section,
              text.isLorcode(),
              preparedImage, 
              TopicPermissionService.getPostScoreInfo(postscore),
              remark);
    } catch (PollNotFoundException e) {
      throw new RuntimeException(e);
    }
  }
  
  private PreparedImage prepareImage(@Nonnull Image image, boolean secure) {
    Preconditions.checkNotNull(image);

    String mediumName = image.getMedium();

    String htmlPath = siteConfig.getHTMLPathPrefix();
    if (!new File(htmlPath, mediumName).exists()) {
      mediumName = image.getIcon();
    }

    try {
      ImageInfo mediumImageInfo = new ImageInfo(htmlPath + mediumName);
      ImageInfo fullInfo = new ImageInfo(htmlPath + image.getOriginal());
      LorURL medURI = new LorURL(siteConfig.getMainURI(), siteConfig.getMainUrl()+mediumName);
      LorURL fullURI = new LorURL(siteConfig.getMainURI(), siteConfig.getMainUrl()+image.getOriginal());

      boolean existsMedium2x = Files.exists(new File(htmlPath, image.getMedium2x()).toPath());

      return new PreparedImage(medURI.fixScheme(secure), mediumImageInfo, fullURI.fixScheme(secure), fullInfo, image, existsMedium2x);
    } catch (BadImageException e) {
      logger.warn("Failed to prepare image", e);
      return null;
    } catch (IOException e) {
      logger.warn("Failed to prepare image", e);
      return null;
    }
  }

  /**
   * Подготовка ленты топиков для пользователя
   * сообщения рендерятся со свернутым cut
   * @param messages список топиков
   * @param secure является ли соединение https
   * @param user пользователь
   * @param profile профиль пользователя
   * @param loadUserpics флаг загрузки аватар
   * @return список подготовленных топиков
   */
  public List<PersonalizedPreparedTopic> prepareMessagesForUser(
          List<Topic> messages,
          boolean secure,
          User user,
          Profile profile,
          boolean loadUserpics
  ) {
    List<PersonalizedPreparedTopic> pm = new ArrayList<>(messages.size());

    Map<Integer,MessageText> textMap = loadTexts(messages);
    ImmutableListMultimap<Integer,TagRef> tags = topicTagService.getTagRefs(messages);

    for (Topic message : messages) {
      PreparedTopic preparedMessage = prepareMessage(
              message,
              tags.get(message.getId()),
              true,
              null,
              secure,
              user,
              textMap.get(message.getId()),
              null
      );

      TopicMenu topicMenu = getTopicMenu(
              preparedMessage,
              user,
              profile,
              loadUserpics
      );

      pm.add(new PersonalizedPreparedTopic(preparedMessage, topicMenu));
    }

    return pm;
  }

  private Map<Integer, MessageText> loadTexts(List<Topic> messages) {
    return msgbaseDao.getMessageText(Lists.transform(messages, Topic::getId));
  }

  /**
   * Подготовка ленты топиков, используется в TopicListController например
   * сообщения рендерятся со свернутым cut
   * @param messages список топиков
   * @param secure является ли соединение https
   * @return список подготовленных топиков
   */
  public List<PreparedTopic> prepareMessages(List<Topic> messages, boolean secure) {
    List<PreparedTopic> pm = new ArrayList<>(messages.size());

    Map<Integer,MessageText> textMap = loadTexts(messages);
    ImmutableListMultimap<Integer,TagRef> tags = topicTagService.getTagRefs(messages);

    for (Topic message : messages) {
      PreparedTopic preparedMessage = prepareMessage(
              message,
              tags.get(message.getId()),
              true,
              null,
              secure,
              null,
              textMap.get(message.getId()),
              null
      );

      pm.add(preparedMessage);
    }

    return pm;
  }

  @Nonnull
  public TopicMenu getTopicMenu(
          @Nonnull PreparedTopic message,
          @Nullable User currentUser,
          Profile profile,
          boolean loadUserpics
  ) {
    boolean topicEditable = groupPermissionService.isEditable(message, currentUser);
    boolean tagsEditable = groupPermissionService.isTagsEditable(message, currentUser);
    boolean resolvable;
    boolean deletable;

    if (currentUser!=null) {
      resolvable = (currentUser.isModerator() || (message.getAuthor().getId()==currentUser.getId())) &&
            message.getGroup().isResolvable();

      deletable = groupPermissionService.isDeletable(message.getMessage(), currentUser);
    } else {
      resolvable = false;
      deletable = false;
    }

    Userpic userpic = null;

    if (loadUserpics && profile.isShowPhotos()) {
      userpic = userService.getUserpic(
              message.getAuthor(),
              profile.getAvatarMode(),
              true
      );
    }

    return new TopicMenu(
            topicEditable,
            tagsEditable,
            resolvable, 
            topicPermissionService.isCommentsAllowed(message.getGroup(), message.getMessage(), currentUser),
            deletable,
            userpic
    );
  }
}

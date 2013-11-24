package com.matejdro.pebblenotificationcenter;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.SparseArray;

import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.util.PebbleDictionary;
import com.matejdro.pebblenotificationcenter.notifications.NotificationHandler;
import com.matejdro.pebblenotificationcenter.notifications.JellybeanNotificationListener;
import com.matejdro.pebblenotificationcenter.util.TextUtil;

public class PebbleTalkerService extends Service {
	private static PebbleTalkerService instance;


	private SharedPreferences settings;
	private NotificationHistoryStorage historyDb;;

	private NotificationListAdapter listHandler;

	private long lastCommunicationTime = 0;
	
	private boolean removingNotifications = false;
	private Queue<Integer> notificationRemovalQueue = new ArrayDeque<Integer>();
	
	PendingNotification curSendingNotification;
	private Queue<PendingNotification> sendingQueue = new ArrayDeque<PendingNotification>();
	private SparseArray<PendingNotification> sentNotifications = new SparseArray<PendingNotification>();


	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onDestroy() {
		instance = null;
		historyDb.close();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		instance = this;
		settings = PreferenceManager.getDefaultSharedPreferences(this);
		historyDb = new NotificationHistoryStorage(this);

		if (intent != null && intent.hasExtra("id"))
		{
			int id = intent.getIntExtra("id", -1);
			String title = intent.getStringExtra("title");
			String pkg = intent.getStringExtra("pkg");
			
			String tag = intent.getStringExtra("tag");
			String subtitle = intent.getStringExtra("subtitle");
			String text = intent.getStringExtra("text");
			boolean dismissable = intent.getBooleanExtra("dismissable", false);
			boolean noHistory = intent.getBooleanExtra("noHistory", false);
			boolean isListNotification = intent.getBooleanExtra("isListNotification", false);

			notifyInternal(id, pkg, tag, title, subtitle, text, dismissable, noHistory, isListNotification);
		}
		else
			appOpened();

		return super.onStartCommand(intent, flags, startId);
	}

	private void send(PendingNotification notification)
	{
		Log.d("Notification Center", "Send " + notification.id);

		curSendingNotification = notification;
		sentNotifications.put(notification.id, notification);

		PebbleDictionary data = new PebbleDictionary();

		byte[] configBytes = new byte[3];
		
		byte flags = 0;
		flags |= (byte) (notification.dismissable ? 0x01 : 0);
		flags |= (byte) (notification.isListNotification ? 0x2 : 0);
		flags |= (byte) (settings.getBoolean("autoSwitch", false) ? 0x4 : 0);
		flags |= (byte) (settings.getBoolean("vibratePeriodically", true) ? 0x8 : 0);

		configBytes[0] = Byte.parseByte(settings.getString("textSize", "0")); //Text size
		configBytes[1] = flags; //Flags
		
		int timeout = 0;
		try
		{
			timeout = Math.min(30000, Integer.parseInt(settings.getString("watchappTimeout", "0")));
		}
		catch (NumberFormatException e)
		{
		}
		
 		data.addUint8(0, (byte) 0);
		data.addInt32(1, notification.id);
		data.addBytes(2, configBytes);
		data.addUint16(3, (short) timeout);
		data.addUint8(4, (byte) notification.textChunks.size());
		data.addString(5, notification.title);
		data.addString(6, notification.subtitle);

		PebbleKit.sendDataToPebble(this, DataReceiver.pebbleAppUUID, data);

		lastCommunicationTime = System.currentTimeMillis();

		PebbleKit.startAppOnPebble(this, DataReceiver.pebbleAppUUID);
	}

	private void dismissOnPebble(Integer id, boolean dontClose)
	{
		Log.d("Notification Center", "Dismissing upwards...");

		PebbleDictionary data = new PebbleDictionary();

		data.addUint8(0, (byte) 4);
		data.addInt32(1, id);
		if (dontClose)
			data.addUint8(2, (byte) 1);

		PebbleKit.sendDataToPebble(this, DataReceiver.pebbleAppUUID, data);
	}
	private void dismissOnPebbleInternal(Integer androidId, String pkg, String tag, boolean dontClose)
	{		
		Log.d("NC Upwards debug", "got dismiss: " + pkg + " " + androidId + " " + tag);

		boolean syncDismissUp = settings.getBoolean("syncDismissUp", true);
		if (!syncDismissUp)
			return;
		
		for (int i = 0; i < sentNotifications.size(); i++)
		{
			PendingNotification notification = sentNotifications.valueAt(i);
			
			if (!notification.isListNotification && notification.androidID != null && notification.androidID.intValue() == androidId.intValue() && notification.pkg != null && notification.pkg.equals(pkg) && (notification.tag == null || notification.tag.equals(tag)))
			{
				if (removingNotifications || sendingQueue.size() > 0)
				{
					notificationRemovalQueue.add(notification.id);
					return;
				}
				dismissOnPebble(notification.id, dontClose);
				
				removingNotifications = true;
				
				break;
			}
		}
	}
	
	private void dismissOnPebbleSucceeded(PebbleDictionary data)
	{
		if (data.contains(2))
		{
			closeApp();
			return;
		}

		Integer nextDismiss = notificationRemovalQueue.poll();
		if (nextDismiss == null)
		{
			removingNotifications = false;
			
			if (curSendingNotification != null)
			{
				send(curSendingNotification);
				return;
			}

			PendingNotification next = sendingQueue.poll();
			if (next != null)
			{
				send(next);
			}
			
			return;
		}
		else
		{
			dismissOnPebble(nextDismiss, sendingQueue.size() > 0);
		}
	}
	
	private void notifyInternal(Integer androidID, String pkg, String tag, String title, String subtitle, String text, boolean dismissable, boolean noHistory, boolean isListNotification)
	{
		Log.d("Notification center", "notify internal");

		text = TextUtil.prepareString(text, 1000);
		
		PendingNotification notification = new PendingNotification();
		notification.androidID = androidID;
		notification.pkg = pkg;
		notification.tag = tag;
		notification.title = TextUtil.prepareString(title, 30);
		notification.subtitle = TextUtil.prepareString(subtitle, 30);
		notification.text = text;
		notification.dismissable = dismissable;
		notification.isListNotification = isListNotification;		

		Log.d("NC Upwards debug", "got notify: " + pkg + " " + androidID + " " + tag);
		
		if (!noHistory)
			historyDb.storeNotification(System.currentTimeMillis(), title, subtitle, text);

		if (!isListNotification)
		{
			if (notification.androidID != null)
			{
				//Preventing spamming of equal notifications
				for (int i = 0; i < sentNotifications.size(); i++)
				{
					PendingNotification comparing = sentNotifications.valueAt(i);
					if (!notification.isListNotification && notification.androidID == comparing.androidID && comparing.text.equals(notification.text) && comparing.title.equals(notification.title) && comparing.subtitle.equals(notification.subtitle))
					{
						return;
					}
				}
				
				dismissOnPebbleInternal(notification.androidID, notification.pkg, notification.tag, true);
			}

			if (settings.getBoolean("noNotifications", false))
				return;

			if (settings.getBoolean("noNotificationsScreenOn", false))
			{
				PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
				if (pm.isScreenOn())
					return;
			}
			
			if (settings.getBoolean("enableQuietTime", false))
			{
				int startHour = settings.getInt("quiteTimeStartHour", 0);
				int startMinute = settings.getInt("quiteTimeStartMinute", 0);
				int startTime = startHour * 60 + startMinute;
				
				int endHour = settings.getInt("quiteTimeEndHour", 23);
				int endMinute = settings.getInt("quiteTimeEndMinute", 59);
				int endTime = endHour * 60 + endMinute;
				
				Calendar calendar = Calendar.getInstance();
				int curHour = calendar.get(Calendar.HOUR_OF_DAY);
				int curMinute = calendar.get(Calendar.MINUTE);
				int curTime = curHour * 60 + curMinute;

				
				if ((endTime > startTime && curTime <= endTime && curTime >= startTime) || (endTime < startTime && (curTime <= endTime || curTime >= startTime)))
				{
					return;
				}
			}
			
			if (settings.getBoolean("noNotificationsNoPebble", false) && !PebbleKit.isWatchConnected(this))
				return;
		}

		Random rnd = new Random();
		do
		{
			notification.id = rnd.nextInt();
		}
		while (sentNotifications.get(notification.id) != null);

		while (text.length() > 0)
		{
			String chunk = TextUtil.trimString(text, 80, false);
			notification.textChunks.add(chunk);
			text = text.substring(chunk.length());
		}

		if (System.currentTimeMillis() - lastCommunicationTime > 2000 && curSendingNotification != null)
		{
			sendingQueue.add(curSendingNotification);
			curSendingNotification = null;
		}

		Log.d("PebbleNotifier", "not sending flags:" + (curSendingNotification != null) + " " + removingNotifications);
		
		if (curSendingNotification != null || removingNotifications )
		{
			sendingQueue.add(notification);
			PebbleKit.startAppOnPebble(this, DataReceiver.pebbleAppUUID);
		}
		else
			send(notification);
	}

	private void closeApp()
	{
		//Launch glance
		if (settings.getBoolean("launchGlance", false))
			PebbleKit.startAppOnPebble(this, UUID.fromString("4B760064-1488-4044-967A-1B1D3AB30574"));
		else
			PebbleKit.closeAppOnPebble(this, DataReceiver.pebbleAppUUID);
		
		Editor editor = settings.edit();
		editor.putLong("lastClose", System.currentTimeMillis());
		editor.apply();
		
		stopSelf();
	}

	private void appOpened()
	{
		if (curSendingNotification != null)
		{
			send(curSendingNotification);
			return;
		}

		PendingNotification next = sendingQueue.poll();
		if (next != null)
		{
			send(next);
			return;
		}

		Log.i("Notification Center", "Sending notification list");

		if (NotificationHandler.isNotificationListenerSupported())
		{
			PebbleDictionary data = new PebbleDictionary();
			data.addUint8(0, (byte) 3);

			PebbleKit.sendDataToPebble(this, DataReceiver.pebbleAppUUID, data);
		}
		else
		{
			listHandler = new RecentNotificationsAdapter(this, historyDb);
			listHandler.sendNotification(0);
		}
	}

	private void menuPicked(PebbleDictionary data)
	{
		int index = data.getUnsignedInteger(1).intValue();
		if (index == 1 || !NotificationHandler.isNotificationListenerSupported())
		{
			listHandler = new RecentNotificationsAdapter(this, historyDb);
			listHandler.sendNotification(0);
		}
		else 
		{
			listHandler = new ActiveNotificationsAdapter(this);
			listHandler.sendNotification(0);
		}

	}

	private void moreTextRequested(PebbleDictionary data)
	{
		int id = data.getInteger(1).intValue();

		PendingNotification notification = sentNotifications.get(id);
		if (notification == null)
		{
			notificationTransferCompleted();
			return;
		}

		int chunk = data.getUnsignedInteger(2).intValue();

		if (notification.textChunks.size() <= chunk)
		{
			notificationTransferCompleted();
			return;
		}

		data = new PebbleDictionary();

		data.addUint8(0, (byte) 1);
		data.addInt32(1, id);
		data.addUint8(2, (byte) chunk);
		data.addString(3, notification.textChunks.get(chunk));
		
		PebbleKit.sendDataToPebble(this, DataReceiver.pebbleAppUUID, data);

		lastCommunicationTime = System.currentTimeMillis();
	}

	private void notificationTransferCompleted()
	{
		curSendingNotification = null;
		PendingNotification next = sendingQueue.poll();
		if (next != null)
			send(next);	
		else
		{
			if (notificationRemovalQueue.size() > 0)
			{
				Integer nextRemovalNotifiaction = notificationRemovalQueue.poll();
				dismissOnPebble(nextRemovalNotifiaction, false);
			}
			//Clean up excess history entries every day
			long lastDbCleanup = settings.getLong("lastCleanup", 0);
			if (System.currentTimeMillis() - lastDbCleanup > 1000 * 3600 * 24)
			{
				historyDb.cleanDatabase();
			}
		}
	}

	private void dismissRequested(PebbleDictionary data)
	{
		int id = data.getInteger(1).intValue();

		Log.d("Notification Center", "dismiss requested " + id);


		PendingNotification notification = sentNotifications.get(id);
		if (notification == null)
		{
			Log.d("Notification Center", "dismiss unknown ");

			return;
		}

		JellybeanNotificationListener.dismissNotification(notification.pkg, notification.tag, notification.androidID);
		
		if (data.contains(2))
			closeApp();
	}

	private void packetInternal(int id, PebbleDictionary data)
	{
		switch (id)
		{
		case 0:
			appOpened();
			break;
		case 1:
			moreTextRequested(data);
			break;
		case 2:
			notificationTransferCompleted();
			break;
		case 3:
			dismissRequested(data);
			break;
		case 4:
			if (listHandler != null) listHandler.gotRequest(data);
			break;
		case 5:
			if (listHandler != null) listHandler.entrySelected(data);
			break;
		case 6:
			menuPicked(data);
			break;
		case 7:
			closeApp();
			break;
		case 8:
			if (listHandler != null) listHandler.sendRelativeNotification(data);
			break;
		case 9:
			dismissOnPebbleSucceeded(data);
			break;
		}
	}

	public static void notify(Context context, String title, String text)
	{
		notify(context, title, text, false);
	}

	public static void notify(Context context, String title, String text, boolean noHistory)
	{
		//Attempt to figure out subtitle
		String subtitle = "";

		if (text.contains("\n"))
		{
			int firstLineBreak = text.indexOf('\n');
			if (firstLineBreak < 40 && firstLineBreak < text.length() * 0.8)
			{
				subtitle = text.substring(0, firstLineBreak).trim();
				text = text.substring(firstLineBreak).trim();
			}

		}

		notify(context, title, subtitle, text, noHistory, false);
	}

	public static void notify(Context context, String title, String subtitle, String text)
	{
		notify(context, title, subtitle, text, false, false);
	}

	public static void notify(Context context, String title, String subtitle, String text, boolean noHistory, boolean isListNotification)
	{
		notify(context, null, null, null, title, subtitle, text, false, noHistory, isListNotification);
	}

	public static void notify(Context context, Integer id, String pkg, String tag, String title, String subtitle, String text, boolean dismissable)
	{
		notify(context, id, pkg, tag, title, subtitle, text, dismissable, false, false);
	}

	public static void notify(Context context, Integer id, String pkg, String tag, String title, String subtitle, String text, boolean dismissable, boolean noHistory, boolean isListNotification)
	{
		if (title == null)
			title = "";
		if (subtitle == null)
			subtitle = "";
		if (subtitle.trim().equalsIgnoreCase(title.trim()))
			subtitle = "";
		if (text == null)
			text = "";

		Log.d("Notification Center", "notify");
		PebbleTalkerService service = PebbleTalkerService.instance;

		if (service == null)
		{
			Intent startIntent = new Intent(context, PebbleTalkerService.class);

			startIntent.putExtra("id", id);
			startIntent.putExtra("pkg", pkg);
			startIntent.putExtra("tag", tag);
			startIntent.putExtra("title", title);
			startIntent.putExtra("subtitle", subtitle);
			startIntent.putExtra("text", text);
			startIntent.putExtra("dismissable", dismissable);
			startIntent.putExtra("noHistory", noHistory);
			startIntent.putExtra("isListNotification", isListNotification);

			context.startService(startIntent);
		}
		else
		{
			service.notifyInternal(id, pkg, tag, title, subtitle, text, dismissable, noHistory, isListNotification);
		}
	}
	
	public static void dismissOnPebble(Integer id, String pkg, String tag)
	{
		PebbleTalkerService service = PebbleTalkerService.instance;

		if (service != null)
		{
			service.dismissOnPebbleInternal(id, pkg, tag, false);
		}
	}

	public static void gotPacket(final Context context, final int packetId, final PebbleDictionary data)
	{
		PebbleTalkerService service = PebbleTalkerService.instance;

		if (service == null)
		{
			Intent startIntent = new Intent(context, PebbleTalkerService.class);
			context.startService(startIntent);
		}
		else
		{
			service.packetInternal(packetId, data);
		}
	}
}

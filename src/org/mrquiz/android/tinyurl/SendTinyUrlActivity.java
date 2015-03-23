/*-
 *  Copyright (C) 2009 Peter Baldwin   
 *  
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mrquiz.android.tinyurl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URLEncoder;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.mrquiz.android.tinyurl.R;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.ClipboardManager;
import android.util.Log;
import android.widget.Toast;

// TODO: Save state
public class SendTinyUrlActivity extends Activity implements 
    Runnable, Handler.Callback {

    static final int HANDLE_URL = 1;

    static final int HANDLE_ERROR = 2;

    static final int HANDLE_TEXT = 3;

    private final Handler mHandler = new Handler(this);

    private String mUrl;

    private String mTinyUrl;
    
    private Toast mToast;
    
    private Boolean mNeedCopy;
    
    private Boolean mFinish;

    /**
     * {@inheritDoc}
     */
    public boolean handleMessage(Message msg) {
        if (isFinishing()) {
            return false;
        }
        switch (msg.what) {
            case HANDLE_URL:
                String url = (String) msg.obj;
                handleUrl(url);
                return true;
            case HANDLE_ERROR:
                Throwable t = (Throwable) msg.obj;
                handleError(t);
                return true;
            case HANDLE_TEXT:
                String text = (String) msg.obj;
                handleText(text);
                return true;
            default:
                return false;
        }
    }

    void handleUrl(String url) {
        mTinyUrl = url;

        send();
        
        if (mNeedCopy)
        {
            copy();
        }
        
        finish();
    }

    void handleError(Throwable throwable) {
        String text = String.valueOf(throwable);
        
        showToast("error : " + text);
        
        finish();
    }
    
    void handleText(String text) {        
        showToast(text);
        
        if (mFinish)
        {
            finish();
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        mUrl = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (mUrl == null) {
            // Use a default URL if the activity is launched directly.
            mUrl = "http://www.google.com/";
        }

        //moveTaskToBack(true);

        // Request a TinyShare on a background thread.
        // This request is fast, so don't worry about the activity being
        // re-created if the keyboard is opened.
        new Thread(this).start();
    }


    private void send() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");

        Intent originalIntent = getIntent();
        if (Intent.ACTION_SEND.equals(originalIntent.getAction())) {
            // Copy extras from the original intent because they miht contain
            // additional information about the URL (e.g., the title of a
            // YouTube video). Do this before setting Intent.EXTRA_TEXT to avoid
            // overwriting the TinyShare.
            intent.putExtras(originalIntent.getExtras());
        }

        intent.putExtra(Intent.EXTRA_TEXT, mTinyUrl);
        try {
            CharSequence template = getText(R.string.title_send);
            String title = String.format(String.valueOf(template), mTinyUrl);
            startActivity(Intent.createChooser(intent, title));
        } catch (ActivityNotFoundException e) {
            handleError(e);
        }
    }

    private void copy() {
        Object service = getSystemService(CLIPBOARD_SERVICE);
        ClipboardManager clipboard = (ClipboardManager) service;
        clipboard.setText(mTinyUrl);

        // Let the user know that the copy was successful.    
        String text = getText(R.string.message_copied) + " : " + mTinyUrl;
        showToast(text);
    }
    
    private void showToast(String text)
    {
        if (mToast == null)
        {
            mToast = Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT);
        }
        else
        {
            mToast.setText(text);
        }
        mToast.show();
    }

    /**
     * Sends the TinyShare to the event thread.
     * 
     * @param url the TinyShare.
     */
    private void sendUrl(String url, Boolean needCopy) {
        mNeedCopy = needCopy;
        mHandler.obtainMessage(HANDLE_URL, url).sendToTarget();
    }

    /**
     * Sends an error to the event thread.
     * 
     * @param t the error.
     */
    private void sendError(Throwable t) {
        mHandler.obtainMessage(HANDLE_ERROR, t).sendToTarget();
    }
    
    private void sendText(String text, Boolean needFinish) {
        mFinish = needFinish;
        mHandler.obtainMessage(HANDLE_TEXT, text).sendToTarget();
    }
    
    public String getTinyUrl()
    {
        String finalUrl = null;
        try {
            String text = getText(R.string.title_creating) + "";
            sendText(text, false);
            
            HttpClient client = new DefaultHttpClient();
            String urlTemplate = "http://tinyurl.com/api-create.php?url=%s";
            String uri = String.format(urlTemplate, URLEncoder.encode(mUrl));
            HttpGet request = new HttpGet(uri);
            HttpResponse response = client.execute(request);
            HttpEntity entity = response.getEntity();
            InputStream in = entity.getContent();
            try {
                StatusLine statusLine = response.getStatusLine();
                int statusCode = statusLine.getStatusCode();
                if (statusCode == HttpStatus.SC_OK) {
                    // TODO: Support other encodings
                    String enc = "utf-8";
                    Reader reader = new InputStreamReader(in, enc);
                    BufferedReader bufferedReader = new BufferedReader(reader);
                    String tinyUrl = bufferedReader.readLine();
                    if (tinyUrl != null) {
                        finalUrl = tinyUrl;
                    } else {
                        throw new IOException("empty response");
                    }
                } else {
                    String errorTemplate = "unexpected response: %d";
                    String msg = String.format(errorTemplate, statusCode);
                    throw new IOException(msg);
                }
            } finally {
                in.close();
            }
        } catch (IOException e) {
            sendError(e);
        } catch (RuntimeException e) {
            sendError(e);
        } catch (Error e) {
            sendError(e);
        }
        
        return finalUrl;
    }

    /**
     * {@inheritDoc}
     */
    public void run() {

        String finalUrl = mUrl;

        if (Util.isValidUrl(mUrl)) 
        {
            
            if (mUrl.indexOf("http://tinyurl.com/") == 0)
            {
                sendUrl(mUrl, false); // got the tinyurl before
            }
            else if (!networkConnected())
            {
                String text = getText(R.string.message_network_not_available) + "";
                sendText(text, true);
            }
            else
            {
                finalUrl = getTinyUrl();
                
                if (finalUrl != null)
                {
                    sendUrl(finalUrl, true);
                }
                else
                {
                    sendUrl(mUrl, false);
                }
            }
        }
        else
        {
            sendText(getText(R.string.message_invalid_url) + "", true);
        }
    }
    
    private boolean networkConnected() {
        boolean result = false;
        ConnectivityManager CM = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);       
        if (CM == null) {
            result = false;
        } else {
            NetworkInfo info = CM.getActiveNetworkInfo(); 
            if (info != null && info.isConnected()) {
                if (!info.isAvailable()) {
                    result = false;
                } else {
                    result = true;
                }
            }
        }
        return result;
    }
}

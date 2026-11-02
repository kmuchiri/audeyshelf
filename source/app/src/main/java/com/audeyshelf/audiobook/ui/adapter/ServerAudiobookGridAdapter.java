package com.audeyshelf.audiobook.ui.adapter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.audeyshelf.audiobook.R;
import com.audeyshelf.audiobook.model.Audiobook;
import com.audeyshelf.audiobook.util.ImageCache;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thumbnail grid adapter for Audiobookshelf server audiobooks.
 * Displays square cover art with a green corner badge for on-device books.
 */
public class ServerAudiobookGridAdapter extends BaseAdapter {

    public interface OnAudiobookClickListener {
        void onAudiobookClick(Audiobook audiobook);
    }

    private final Context context;
    private final LayoutInflater inflater;
    private List<Audiobook> books = new ArrayList<>();
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OnAudiobookClickListener listener;

    public ServerAudiobookGridAdapter(Context context, List<Audiobook> books) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        setAudiobooks(books);
    }

    public void setOnAudiobookClickListener(OnAudiobookClickListener listener) {
        this.listener = listener;
    }

    public void setAudiobooks(List<Audiobook> newBooks) {
        this.books = newBooks != null ? new ArrayList<>(newBooks) : new ArrayList<>();
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return books.size();
    }

    @Override
    public Audiobook getItem(int position) {
        return books.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    static class GridViewHolder {
        ImageView ivCover;
        View layoutFallback;
        TextView tvFallbackTitle;
        View badgeOnDevice;
        String boundCoverUrl;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final GridViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_audiobook_grid, parent, false);
            holder = new GridViewHolder();
            holder.ivCover = convertView.findViewById(R.id.ivGridCover);
            holder.layoutFallback = convertView.findViewById(R.id.layoutGridFallback);
            holder.tvFallbackTitle = convertView.findViewById(R.id.tvGridFallbackTitle);
            holder.badgeOnDevice = convertView.findViewById(R.id.badgeGridOnDevice);
            convertView.setTag(holder);
        } else {
            holder = (GridViewHolder) convertView.getTag();
        }

        final Audiobook book = getItem(position);
        holder.boundCoverUrl = book.getCoverUrl();

        // Green corner badge if book is on device
        if (holder.badgeOnDevice != null) {
            holder.badgeOnDevice.setVisibility(book.isOnDevice() ? View.VISIBLE : View.GONE);
        }

        // Item click listener
        convertView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onAudiobookClick(book);
            }
        });

        // Cover art loading
        holder.ivCover.setImageResource(R.drawable.ic_book_placeholder);
        holder.layoutFallback.setVisibility(View.GONE);

        if (book.getCoverUrl() != null && !book.getCoverUrl().isEmpty()) {
            final String coverUrl = book.getCoverUrl();
            Bitmap cached = ImageCache.getInstance().get(coverUrl);
            if (cached != null) {
                holder.ivCover.setImageBitmap(cached);
                holder.layoutFallback.setVisibility(View.GONE);
            } else {
                imageExecutor.execute(() -> {
                    final Bitmap remoteCover = downloadCover(coverUrl);
                    if (remoteCover != null) {
                        ImageCache.getInstance().put(coverUrl, remoteCover);
                    }
                    mainHandler.post(() -> {
                        if (coverUrl.equals(holder.boundCoverUrl)) {
                            if (remoteCover != null) {
                                holder.ivCover.setImageBitmap(remoteCover);
                                holder.layoutFallback.setVisibility(View.GONE);
                            } else {
                                holder.layoutFallback.setVisibility(View.VISIBLE);
                                holder.tvFallbackTitle.setText(book.getTitle());
                            }
                        }
                    });
                });
            }
        } else {
            holder.layoutFallback.setVisibility(View.VISIBLE);
            holder.tvFallbackTitle.setText(book.getTitle());
        }

        return convertView;
    }

    private Bitmap downloadCover(String urlStr) {
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            URL url = new URL(urlStr);
            conn = com.audeyshelf.audiobook.net.AudiobookshelfClient.openConnection(url);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setDoInput(true);
            conn.connect();

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                is = conn.getInputStream();
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2; // Downsample for minimal memory on legacy devices
                return BitmapFactory.decodeStream(is, null, opts);
            }
        } catch (Throwable ignored) {
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }
}
